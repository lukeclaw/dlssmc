# DLSSmc — Product Requirements Document

**Status:** Draft v0.1 · **Last updated:** 2026-07-10 · **Owner:** Jose
**Target game version (pinned):** Minecraft Java Edition `26.3-snapshot-3`
**Repo module id:** `dlssmc` · **Maven group:** `com.jhp`

> Companion documents: [`PROJECT_TRACKER.md`](./PROJECT_TRACKER.md) (live plan + status),
> [`SPIKE_FINDINGS.md`](./SPIKE_FINDINGS.md) (empirical evidence behind the decisions here).

---

## 1. Goal

Add real NVIDIA DLSS (Super Resolution) support to Minecraft Java Edition by
piggybacking on Mojang's new experimental Vulkan renderer (`renderpearl`), via a
Fabric mod using SpongePowered Mixin bytecode injection.

**Definition of done (prototype):** In a dev instance of the pinned snapshot with
the Vulkan backend enabled, the 3D world renders at a reduced internal resolution,
is upscaled to output resolution by DLSS through NVIDIA Streamline, and the HUD/UI
composites cleanly at native resolution — with motion handled well enough that a
moving camera does not produce gross ghosting or smearing on chunks and common
entities.

## 2. Background / why this is newly possible

- NVIDIA's DLSS SDK (NGX / Streamline) only ever targeted DX11/DX12/Vulkan — never
  OpenGL. Java Edition was OpenGL-only until 2026, a hard blocker.
- **26.2-snapshot-1** (Apr 2026) introduced an experimental Vulkan renderer
  (Video Settings → Graphics → "Prefer Vulkan (Experimental)").
- **26.1 "Tiny Takeover"** (Mar 2026) shipped the client **deobfuscated** (real
  class/method names — readable, though still proprietary, not open source).
- These two together make Mixin-based DLSS integration tractable for the first time.
- **Bedrock** already has DLSS, but via RenderDragon — an unrelated engine. None of
  that work transfers to Java.

## 3. What DLSS needs per frame (from NVIDIA Streamline / NGX docs)

1. Low-resolution color input (the 3D scene rendered at internal res).
2. Final-resolution output target.
3. Depth buffer.
4. Per-pixel **motion vectors** — accurate, correct scale/space, updated every frame.
5. Sub-pixel **jitter** applied to the projection matrix (16+ phase sequence, e.g. Halton).
6. Exposure / HDR flags.
7. A **reset flag** for camera cuts (teleport, portal, respawn, dimension change).

All fed through NVIDIA Streamline (which fronts NGX/DLSS).

## 4. Scope

### In scope (prototype)
- Fabric mod targeting the pinned snapshot, Vulkan backend only.
- Capturing raw Vulkan handles (`VkDevice`, `VkQueue`s, command buffers, swapchain
  images) from Mojang's `renderpearl` Vulkan backend via Mixin.
- Injecting sub-pixel jitter into the projection matrix.
- Generating per-pixel motion vectors for the major visual classes: terrain chunks,
  entities, particles, water/translucents.
- Decoupling 3D internal render resolution from output resolution; compositing
  HUD/UI at native resolution after upscale.
- Integrating NVIDIA Streamline against Minecraft's own Vulkan device (manual
  hooking) and driving DLSS Super Resolution.
- Firing the DLSS reset flag on camera cuts.
- Basic artifact tuning to reach the "no gross ghosting" bar.

### Out of scope (prototype)
- DLSS Frame Generation, Ray Reconstruction, Reflex (SR only for v1).
- OpenGL backend support (Vulkan only).
- Non-NVIDIA upscalers (FSR/XeSS) — architecture should not preclude them, but they
  are not a deliverable.
- Shipping/redistribution to end users — **gated on the Streamline license review**
  (see Risk 3). Personal-dev-machine use only until resolved.
- Production-grade QA across all GPUs/drivers, all dimensions, all modded content.
- Tracking every weekly snapshot — we pin one version (see §7).

## 5. Success criteria

| # | Criterion | Measure |
|---|-----------|---------|
| S1 | Vulkan handles captured | `VkDevice` + graphics queue logged/non-null at runtime with Vulkan backend on |
| S2 | Jitter active | Projection matrix carries expected per-frame sub-pixel offset (verified numerically) |
| S3 | Resolution decoupled | Internal 3D target renders at <100% while window/UI stays native |
| S4 | Motion vectors correct | Static-world camera pan shows MVs matching screen-space motion (debug viz) |
| S5 | DLSS engaged | Streamline reports DLSS SR active; measurable internal-res reduction at similar output quality |
| S6 | Acceptable motion quality | No gross ghosting/smearing on chunks + common entities during normal play |
| S7 | Reset correctness | Teleport/portal/respawn produce no multi-frame smear |

## 6. Constraints & assumptions

- **Toolchain:** JDK 25, Fabric Loader `0.19.3`, Fabric Loom `1.17-SNAPSHOT`,
  Fabric API `0.154.3+26.3`, Java compat level 25, Mixin `JAVA_25`.
- **No Yarn mappings** — 26.1+ ships deobfuscated; Loom retired Yarn.
- **Renderer internals are volatile** — Vulkan/`renderpearl` code is changing weekly;
  we pin and bump deliberately.
- **Hardware:** an NVIDIA RTX GPU with a driver exposing Vulkan 1.2 + dynamic
  rendering + push descriptors, plus DLSS support.
- **LWJGL Vulkan bindings** are what `renderpearl` uses (`org.lwjgl.vulkan.VkDevice` /
  `VkQueue`), so raw native handles are reachable via `.address()`.

## 7. Versioning policy

Pin `minecraft_version` in `gradle.properties`. Do **not** auto-track new snapshots.
Bump deliberately and re-verify all Mixin targets each time, because renderer
internals shift between snapshots.

## 8. Risks (post-spike)

Full evidence in [`SPIKE_FINDINGS.md`](./SPIKE_FINDINGS.md). Summary:

| # | Risk | Status after spike | Impact |
|---|------|--------------------|--------|
| R1 | Does Vibrant Visuals already provide motion vectors / TAA / jitter we can reuse? | **Resolved — NO.** No motion/velocity/TAA/jitter/temporal/deferred/G-buffer code or shaders exist in `26.3-snapshot-3`. | Motion-vector work stands at **full cost (~3–5 wks)**. No shortcut. |
| R2 | Can Streamline hook a Vulkan device Minecraft itself owns, or must Streamline own device creation? | **Resolved — favorable.** Streamline supports **manual hooking** (`slSetVulkanInfo` + `slGetNativeInterface`/`slUpgradeInterface`) precisely for the case where the app owns `vkCreateInstance`/`vkCreateDevice`. | Streamline wiring is feasible without surrendering device creation. New sub-task: inject the VK instance/device **extensions + features** DLSS needs into Minecraft's creation path (automatic mode would have added these silently). |
| R3 | Streamline / DLSS SDK redistribution licensing for a fan-made mod. | **Partially resolved — needs human legal read.** Streamline *framework* is open-source, but the DLSS + SL **binary artifacts ship under NVIDIA's SDK EULA**, not the repo license. Community mods do redistribute them in practice, but that is not confirmation of permission. | **Shipping is gated.** Dev/personal use can proceed. A human must read the EULA in the downloaded release zip before any distribution. |

## 9. High-level approach

Two phases (see tracker for task-level detail):

- **Phase 1 — Rendering rework (mod side):** Vulkan handle capture, jitter injection,
  motion-vector generation, resolution decoupling + UI compositing.
- **Phase 2 — DLSS integration:** Streamline manual-hook wiring against Minecraft's
  device, per-frame buffer tagging (color/depth/MV/jitter/exposure), reset flag,
  artifact tuning. Gated by the license review before shipping.

## 10. Open questions carried forward

- Exact `@Inject` method descriptors for the identified target classes must be
  confirmed against Loom `genSources` output in-IDE (spike used constant-pool
  inspection, which is reliable for names but not a substitute for the decompiled
  signatures). See tracker task list.
- Does `renderpearl` already enable NV-relevant device extensions? The presence of
  `NvidiaCheckpointExtension` suggests some NV-aware paths already exist — worth
  checking which extensions/features are enabled before we add our own.
- Depth buffer format/space and whether a linear depth conversion is needed for DLSS.
- Whether particles/weather need dedicated MV passes or can inherit camera-only MVs
  acceptably for the prototype bar.
