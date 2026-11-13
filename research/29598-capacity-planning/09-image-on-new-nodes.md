# Getting the Trino image onto new nodes faster (2026-11-13)

## Problem

Trino workers scale up shortly before a heavy query runs. Karpenter starts new nodes for them. A new node
must pull the container image before the worker can start. An earlier estimate put this pull at about
20–60 seconds for each new node.

This note compares the ways to remove or shorten that pull. CNCF status was read from cncf.io and the
CNCF landscape file on 2026-11-13. Nothing in this note was tried on a cluster. Each row says if I read the
claim in the project's own documentation ("checked") or not ("not checked").

## Terms

- **Cold node:** a new node with nothing in its image cache.
- **Lazy loading:** the container starts before the full image is downloaded. Files are fetched when they are first read.
- **Snapshotter:** the containerd plug-in that prepares the image files on disk.
- **P2P (peer-to-peer):** nodes copy image data from each other, not from the registry.
- **Pre-pull:** a controller pulls the image onto nodes before a workload needs it.

## Summary

1. **Make the image smaller and pull it in parallel.** Almost all of the pull is one 852 MB layer (the Trino
   server with all plugins). An image with only the necessary plugins is about half the size. SOCI parallel
   pull downloads a layer over many connections at the same time. No new cluster component is necessary.
2. **Put the image on the node disk before the node starts.** A Bottlerocket data-volume snapshot contains
   the image at boot. This gives a pull time near zero. A new snapshot is necessary for each Trino release.
3. **P2P and pre-pull controllers do little for a cold node.** They help nodes that already exist, or nodes
   with warm peers. Our case is a node that started 30 seconds ago.

## The Trino image

- `trinodb/trino:484-amd64` has 34 gzip layers and is about 1037 MB compressed. Checked in the Docker Hub manifest.
- One layer is 852 MB. It is the server tarball, added by a single `COPY trino-server` in `core/docker/Dockerfile`. The JDK layer is 143 MB.
- Trino also publishes `trinodb/trino-core`. Version 484-amd64 is 468 MB compressed. Its server layer is 283 MB. Checked in the Docker Hub manifest.
- At startup, Trino creates a class loader for every directory in `plugin/` (`ServerPluginsProvider.java`). Lazy loading would probably still read most jars. Not checked.

To measure a custom image:

1. Run `docker run --rm --entrypoint du trinodb/trino:<version> -sh /usr/lib/trino/plugin/*`.
2. Keep only the plugins in use, plus the memory-history plugin.
3. Build the image on `trino-core`.
4. Compare the layer sizes in the two manifests.

## Comparison

| Option | Type | CNCF status | How it works | Helps a cold node? | Needs | Effort | Main risk | Reported gain |
|---|---|---|---|---|---|---|---|---|
| Smaller image (`trinodb/trino-core` or own plugin set) | Smaller image | Not CNCF | Build only the necessary plugins on the core tarball. | Yes | Image build with a plugin list | Low–medium | A missing plugin causes a runtime error | About 55% fewer bytes to pull (manifest sizes, checked) |
| SOCI parallel pull and unpack | Faster pull | Not CNCF (AWS Labs) | Downloads each layer in chunks over many connections. Unpacks layers in parallel. No SOCI index is necessary. | Yes | AL2023 newer than v20250821 or Bottlerocket newer than 1.44. Instance size 2xlarge or larger. Fast disk (gp3 600–1000 MiB/s or NVMe). | Low | Marked experimental. Uses more CPU and disk I/O. One large layer limits the parallel unpack. | AWS: 10 GB image, 1 min 53 s → 45 s. AWS blueprint: 9 GB image, 2 min 11 s → 40 s on AL2023 and 23 s on Bottlerocket (checked, AWS sources) |
| containerd 2.2+ parallel fetch and unpack | Faster pull | containerd: Graduated | Transfer-service settings for concurrent downloads and unpacks. | Yes | containerd 2.2 or newer, explicit configuration | Low–medium | One user report of a small pull-error rate on 2.2.1 (not checked) | None found |
| Bottlerocket data-volume snapshot | Image on the node | Not CNCF | Pull the image one time. Make an EBS snapshot of the data volume. Set the snapshot ID on `/dev/xvdb` in the `EC2NodeClass`. | Yes. The image is on disk at boot. | Bottlerocket. A pipeline step for each image version. | Medium | A volume from a snapshot loads blocks from S3 when first read. Set `volumeInitializationRate` or use Fast Snapshot Restore. | 49 s → 3 s (second-hand figure, not checked) |
| Image in the AMI (Packer, EC2 Image Builder) | Image on the node | Not CNCF | Pull the image into the containerd store of a custom AMI. | Yes | An AMI pipeline | High | Each AMI change makes Karpenter replace nodes (drift). Probably the same first-read cost as snapshots (not checked). | None found |
| zstd-compressed layers | Faster unpack | — | Build with `compression=zstd`. | Yes | Image rebuild and push | Low | Scanner support. SOCI lazy mode did not support zstd in 2023 (not checked for today). | AWS: "up to 27%" faster pod start on Fargate (checked, AWS blog) |
| ECR in the same region, VPC endpoints, pull-through cache | Registry | — | Keeps pulls on the AWS network. The cache stores Docker Hub images in ECR. | Yes | A secret for Docker Hub | Low | The first pull through the cache needs an internet route | None found |
| Local NVMe for containerd (`instanceStorePolicy: RAID0`) | Disk | — | Puts image data on instance-store disks. | Yes | Instance types with NVMe | Low | Fewer usable instance types | None found |
| Spare capacity (placeholder pods, static NodePool) | Avoid cold nodes | — | Low-priority pods hold nodes that already have the image. | Avoids the cold node | Paid idle capacity | Low | Cost | — |
| Spegel | P2P mirror | Not a CNCF project (on the landscape) | Each node serves the images in its cache to other nodes. | Only if another node has the image | Registry-mirror configuration. Bottlerocket 1.56 or newer. | Medium | Conflicts with a SOCI setting. The Spegel pod must run before the pull. Private images become readable by all nodes. | None found |
| Dragonfly | P2P | Graduated (2025-10-28) | Seed peers and peers share pieces of layers. Preheat fills the seed peers. | Partly, from warm seed peers | Manager, scheduler, seed peers, a DaemonSet | High | The peer must be ready before the pull. Many components to operate. | Project claim: more than 80% less network latency (checked, project README) |
| Kraken (Uber) | P2P registry | Not a CNCF project (on the landscape) | An agent on each host, plus origin and tracker services. | Partly | Origins, tracker, agents | High | Its README says there is no gain if the registry is not the bottleneck | Uber: 2600 hosts, 3 GB image, median 10 s (checked, project README) |
| Nydus | Lazy loading | Dragonfly sub-project | A different image format. Chunks load on demand, with prefetch. | Yes | Image conversion. The nydus snapshotter on each node. Not in the EKS AMIs (not checked). | High | Trino reads every plugin directory at startup | Chart only |
| stargz / eStargz | Lazy loading | containerd sub-project | Lazy pull with a prefetch profile. | Yes | Image conversion. A snapshotter daemon on each node. | High | Same startup problem | Chart only |
| SOCI lazy loading (with an index) | Lazy loading | Not CNCF | An index stored next to the image lets the node fetch files on demand. | Yes | An index build in the pipeline | Medium | Large jars are fetched as whole files | AWS: 25.6% faster for a Spring Boot application on Fargate (checked, AWS blog) |
| OpenKruise `ImagePullJob` | Pre-pull | Incubating (2023-03-02) | A daemon pulls the image on selected nodes. | No. It races the worker pod. | A feature gate that is off by default | Medium | No gain on a cold node | — |
| kube-fledged `ImageCache` | Pre-pull | Not CNCF | A controller runs pull jobs and refreshes them. | No | — | Low | No gain on a cold node | — |
| Pre-pull DaemonSet | Pre-pull | — | A DaemonSet pulls the image on each node. | No. The worker pod can start first. | — | Low | Image garbage collection can remove the image | — |
| Harbor preheat | Pre-pull to P2P | Harbor: Graduated | Pushes images to Dragonfly or Kraken. | Only through P2P | Harbor and a P2P engine | High | A second registry to operate | — |
| kubelet parallel image pulls | kubelet setting | Kubernetes | Pulls different images at the same time. | No gain here. One pod has one image. | — | — | — | — |

## Recommended order

1. **Measure the baseline** on the real cluster (see the list below).
2. **Low effort first:**
   - Use ECR in the same region with VPC endpoints.
   - Build a smaller image on `trino-core` with only the necessary plugins.
   - Give new nodes a fast disk: gp3 with at least 600 MiB/s, or local NVMe.
   - Turn on SOCI parallel pull.
3. **Next:** build the image with zstd compression. Optionally split the server layer into more layers, so the parallel unpack has more to do. Not checked.
4. **If the pull must be near zero:** use a Bottlerocket data-volume snapshot with `volumeInitializationRate`, built for each Trino version. Or keep spare capacity.
5. **Later, if still necessary:** Spegel, Dragonfly, Kraken and the lazy-loading snapshotters.

## What to measure on the real cluster

- The time between the `Pulling` and `Pulled` kubelet events (`kubectl get events`).
- The time from node `Ready` to container `Started`.
- The time from container `Started` to `SERVER STARTED` in the Trino log.
- The layer count and layer sizes of the image in use.
- Node CPU, disk throughput and network during the pull.
- The time from Karpenter `NodeClaim` launch to node `Ready`.

## Not checked

- The real gain of SOCI parallel pull for an image with one 852 MB layer. The AWS figures are for 9–10 GB images.
- The state of the containerd 2.2/2.3 parallel pull on EKS AL2023.
- The exact figures in the AWS blog about Bottlerocket snapshots. The 49 s → 3 s figure is second-hand.
- How much of each jar Trino reads at startup. This decides if lazy loading can help.
- How Spegel or Dragonfly start relative to the worker pod on a new Karpenter node.

## Sources

- CNCF landscape: https://github.com/cncf/landscape/blob/master/landscape.yml
- Docker Hub manifests of `trinodb/trino` and `trinodb/trino-core`: https://hub.docker.com/r/trinodb/trino-core
- SOCI snapshotter and parallel mode: https://github.com/awslabs/soci-snapshotter , https://github.com/awslabs/soci-snapshotter/blob/main/docs/parallel-mode.md
- AWS, SOCI parallel pull on EKS: https://aws.amazon.com/blogs/containers/introducing-seekable-oci-parallel-pull-mode-for-amazon-eks/
- Karpenter blueprint for SOCI: https://github.com/aws-samples/karpenter-blueprints/tree/main/blueprints/soci-snapshotter
- EKS best practices, performance: https://docs.aws.amazon.com/eks/latest/best-practices/aiml-performance.html
- AWS, Bottlerocket data volume: https://aws.amazon.com/blogs/containers/reduce-container-startup-time-on-amazon-eks-with-bottlerocket-data-volume/
- EBS volume initialization: https://docs.aws.amazon.com/ebs/latest/userguide/initalize-volume.html
- Karpenter NodeClasses: https://karpenter.sh/docs/concepts/nodeclasses/
- AWS, zstd on Fargate: https://aws.amazon.com/blogs/containers/reducing-aws-fargate-startup-times-with-zstd-compressed-container-images/
- AWS, SOCI with Spring Boot: https://aws.amazon.com/blogs/containers/start-spring-boot-applications-faster-on-aws-fargate-using-soci/
- ECR pull-through cache: https://docs.aws.amazon.com/AmazonECR/latest/userguide/pull-through-cache.html
- Kubernetes images: https://kubernetes.io/docs/concepts/containers/images/
- Spegel: https://spegel.dev/docs/getting-started/ , https://spegel.dev/docs/faq/
- Dragonfly: https://www.cncf.io/projects/dragonfly/ , https://d7y.io/docs/
- Nydus: https://github.com/dragonflyoss/nydus
- Kraken: https://github.com/uber/kraken
- stargz snapshotter: https://github.com/containerd/stargz-snapshotter
- OpenKruise: https://www.cncf.io/projects/openkruise/ , https://openkruise.io/docs/user-manuals/imagepulljob
- kube-fledged: https://github.com/senthilrch/kube-fledged
- Harbor preheat: https://goharbor.io/docs/main/administration/p2p-preheat/
- Trino deployment (core tarball): https://trino.io/docs/current/installation/deployment.html
