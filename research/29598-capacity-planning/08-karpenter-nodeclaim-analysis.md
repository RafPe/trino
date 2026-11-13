# Analysis: creating Karpenter NodeClaims directly from the memory-history setup (2026-11-13)

Question: should the gate (or another part of the PoC) create Karpenter `NodeClaim` objects itself, so
the nodes for extra Trino workers start right away?

Sources: two Sonnet researchers. Karpenter facts were read in the docs (karpenter.sh) and in the code of
kubernetes-sigs/karpenter v1.15.1. Timing figures come mostly from vendor blogs and one benchmark, not from
our own measurements. Nothing here was tried on a cluster.

## Verdict

Possible, but it saves little and adds risk. Karpenter already reacts within seconds to the pending worker
pods that our scale-up creates. A hand-made NodeClaim saves only that reaction time: under about 10 s on a
path of roughly 1.5 to 3 minutes. Other levers save much more.

## Where the time goes (pending pod → active Trino worker, EKS + Karpenter)

| Step | Time | Source quality |
|---|---|---|
| Karpenter batches pending pods | 1 s idle window, 10 s maximum | verified in Karpenter settings docs |
| Node launch and join | about 40–90 s | vendor blogs, one benchmark |
| Image pull, 2–3 GB image | about 20–60 s | third-party measurement, not Trino-specific |
| JVM start and worker registration | about 10–40 s | estimate |

Our own kind demo measured the last part only (pods running → workers active in Trino): about 20 s.

## What Karpenter says about hand-made NodeClaims

- The docs have one sentence: "You can also create NodeClaims manually to request capacity outside of the
  standard provisioning workflow." No example, no guarantees. Status: mentioned and tolerated, not a
  documented workflow. No AWS project was found that does it (Azure gpu-provisioner/KAITO does on Azure).
- Required fields: `nodeClassRef` and `requirements`. The whole spec is immutable.
- A NodeClaim without a NodePool gets **no** consolidation, emptiness handling, drift handling, NodePool
  limits or disruption budgets. It stays until someone deletes it or `expireAfter` fires.
- If the launch fails (for example no capacity for that instance type) the NodeClaim is deleted and
  **nothing retries**. Launch timeout 5 min, registration timeout 15 min.
- Deleting the NodeClaim drains the node and terminates the instance.
- Karpenter's scheduling simulation counts in-flight NodeClaims, also unowned ones. A pending pod that
  selects and tolerates the hand-made node is treated as fitting it, so no second node is provisioned,
  **if** labels, taints and sizes match. A mismatch gives double provisioning. The first moments after
  creation were not verified.
- Needs cluster-wide rights to create and delete `nodeclaims.karpenter.sh`.

## Risks of doing it from our setup

1. **Cost runaway and orphans.** No NodePool limit applies; an empty hand-made node is never cleaned up
   unless we set `expireAfter` or delete it. A retry bug creates many nodes.
2. **We must rebuild what Karpenter already does:** retry on capacity errors, instance-type fallback,
   cleanup, limits.
3. **Double provisioning** if the pod and the node do not match exactly.
4. **Wrong place for it.** The gate is a client-side script that lives only as long as one query. It must
   not own nodes. Something long-lived would have to (the predictor or a small controller).
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

Prefer option 5 (a dedicated static NodePool for Trino whose `replicas` follows the predictor's
`/v1/required-workers`) over hand-made NodeClaims. If NodeClaims must be used:

- A long-lived controller owns them, not the gate.
- One dedicated label and taint; worker pods select and tolerate exactly that.
- Always set `expireAfter`; cap the number of claims; delete claims when the reservation ends.
- Watch for deleted claims (launch failure) and fall back to the normal path (pending pods → Karpenter).
- One worker shape only.

## What to measure first

On the real EKS cluster, time each step for one scale-up of the Trino workers (pod pending → node ready →
image pulled → worker active). That shows which of options 1–4 is worth doing, and whether the 10 s that
NodeClaims could save matters at all.
