# Analysis: creating Karpenter NodeClaims directly from the memory-history setup (2026-11-13)

Question: should the gate (or another part of the PoC) create Karpenter `NodeClaim` objects itself? Then
the nodes for extra Trino workers could start immediately.

Sources: I read the Karpenter facts in the docs (karpenter.sh) and in the code of
kubernetes-sigs/karpenter v1.15.1. Timing figures come mostly from vendor blogs and one benchmark, not from
our own measurements. Nothing here was tried on a cluster.

## Verdict

Possible, but it saves little time and adds risk. Karpenter already reacts within seconds to the pending
worker pods that our scale-up creates. A hand-made NodeClaim saves only that reaction time: under about 10 s
on a path of roughly 1.5 to 3 minutes. Other options save much more.

## Where the time goes (pending pod → active Trino worker, EKS + Karpenter)

| Step | Time | Source quality |
|---|---|---|
| Karpenter batches pending pods | 1 s idle window, 10 s maximum | checked in the Karpenter settings docs |
| Node launch and join | about 40–90 s | vendor blogs, one benchmark |
| Image pull, 2–3 GB image | about 20–60 s | third-party measurement, not Trino-specific |
| JVM start and worker registration | about 10–40 s | estimate |

Our own kind demo measured the last part only (pods running → workers active in Trino): about 20 s.

## What Karpenter says about hand-made NodeClaims

- The docs have one sentence: "You can also create NodeClaims manually to request capacity outside of the
  standard provisioning workflow." No example, no guarantees. Status: mentioned and tolerated, not a
  documented workflow. I found no AWS project that does it (Azure gpu-provisioner/KAITO does it on Azure).
- Required fields: `nodeClassRef` and `requirements`. The whole spec is immutable.
- A NodeClaim without a NodePool gets **no** consolidation, emptiness handling, drift handling, NodePool
  limits or disruption budgets. It stays until someone deletes it or until `expireAfter` fires.
- If the launch fails (for example, no capacity for that instance type), Karpenter deletes the NodeClaim and
  **nothing retries**. Launch timeout 5 min, registration timeout 15 min.
- Deleting the NodeClaim drains the node and terminates the instance.
- The Karpenter scheduling simulation counts in-flight NodeClaims, also unowned ones. A pending pod can
  select and tolerate the hand-made node. Karpenter then treats the pod as fitting that node and provisions
  no second node, **if** labels, taints and sizes match. A mismatch causes double provisioning. Not checked:
  the behavior in the first moments after creation.
- The creator needs cluster-wide rights to create and delete `nodeclaims.karpenter.sh`.

## Risks of doing it from our setup

1. **Cost runaway and orphans.** No NodePool limit applies. Nothing deletes an empty hand-made node unless
   we set `expireAfter` or delete it. A retry bug creates many nodes.
2. **We must rebuild what Karpenter already does:** retry on capacity errors, instance-type fallback,
   cleanup, limits.
3. **Double provisioning** if the pod and the node do not match exactly.
4. **Wrong place for it.** The gate is a client-side script that lives only as long as one query. It must
   not own nodes. A long-lived component would have to own them (the predictor or a small controller).
5. **Broad permissions** for a component that sits in the query path.
6. **Mixed worker sizes.** `query.max-memory-per-node` is one setting for all workers. Hand-picked bigger
   nodes do not help unless all workers and the config change.

## Options that give more, ranked

| # | Option | Saves | Notes |
|---|---|---|---|
| 1 | Predict earlier (2–3 min lead time) | the whole cold path | Needs a gate that sees queries early, or schedule knowledge (ETL start times). |
| 2 | Trino image already on new nodes (Bottlerocket data-volume snapshot, or image in the AMI) | about 20–60 s | Low risk. Our image is 2.4 GB. |
| 3 | Spare capacity: placeholder pods with a negative priority class, or Karpenter `CapacityBuffer` (alpha) | near-instant start for the buffered amount | Costs idle nodes. One benchmark: about 3 s warm vs 76 s cold. |
| 4 | Keep used nodes for a while: `consolidationPolicy: WhenEmpty` + long `consolidateAfter`, `karpenter.sh/do-not-disrupt` on worker pods | repeat scale-ups | Simple. First scale-up is still cold. |
| 5 | Static NodePool (`spec.replicas`, alpha, feature gate off by default), scaled by the predictor | same as hand-made NodeClaims, but owned by Karpenter | Gets retry, drift handling and `limits.nodes` as a cost cap. Cannot choose which node is removed. |
| 6 | Hand-made NodeClaims | under about 10 s | Everything in "Risks" above. |

## If we still want direct node provisioning

Prefer option 5 over hand-made NodeClaims. Option 5 is a dedicated static NodePool for Trino. Its `replicas`
follows the `/v1/required-workers` value of the predictor. If we must use NodeClaims:

- A long-lived controller owns them, not the gate.
- Use one dedicated label and taint. Worker pods select and tolerate exactly that label and taint.
- Always set `expireAfter`.
- Cap the number of claims.
- Delete claims when the reservation ends.
- Watch for deleted claims (launch failure). Then use the normal path (pending pods → Karpenter).
- Use one worker shape only.

## What to measure first

On the real EKS cluster, time each step of one scale-up of the Trino workers (pod pending →
node ready → image pulled → worker active). The result shows which of options 1–4 is worth the work. It also
shows if the 10 s that NodeClaims could save is important at all.
