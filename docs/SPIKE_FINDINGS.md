# Spike Findings — 2026-07-10

Empirical investigation of the three open risks in the project brief, plus
identification of concrete Mixin injection targets for Phase 1.

## Method & caveat

Source of truth was the **deobfuscated `26.3-snapshot-3` client jar** pulled by Loom
into `.gradle/loom-cache/.../minecraft-clientOnly-*.jar` (3,551 client classes).

Inspection was done by scanning class names and **constant-pool string contents**
(`unzip` + `strings`) plus reading the shipped shader assets directly. A full Java
decompiler was not available in the sandbox (no network for a decompiler download;
JRE-only, so no `javap`). **Class, field, and method *names* extracted this way are
reliable; exact method *descriptors* for `@Inject` targets must still be confirmed
against Loom `genSources` in the IDE.** Findings below are labelled accordingly.

---

## Risk 1 — Motion vectors / TAA / jitter already present? → **NO**

Searched all 3,551 client class names and the shader assets for:
`motion, velocity, jitter, taa, temporal, reproject, upscale, deferred, gbuffer,
halton, dlss, fsr, history, prev`.

- **Zero** matching classes. (The only hits were unrelated false positives such as
  `ServerAddressResolver`, `StagingBuffer`, `DeferredPacket`.)
- **No** PBR / deferred / HDR / normal-buffer classes → the Vibrant Visuals deferred
  pipeline has **not landed** in this snapshot.
- A post-processing framework **does** exist — `PostChain`, `PostChainConfig`,
  `PostPass`, plus an order-independent-transparency package
  (`net.minecraft.client.renderer.oit.*`) — but the **shipped post shaders are the
  classic vanilla set only**: `invert, box_blur, entity_outline_box_blur,
  entity_sobel, color_convolve, bits, blit, rotscale, spiderclip`. None are
  temporal/motion/velocity.
- `assets/minecraft/shaders/include/projection.glsl` is a plain, **unjittered**
  `ProjMat` uniform:
  ```glsl
  layout(std140) uniform Projection { mat4 ProjMat; };
  ```

**Conclusion:** There is no motion-vector, TAA, or jitter infrastructure to reuse.
The motion-vector line item stays at full cost (~3–5 weeks) and we must generate
motion vectors ourselves.

**Silver lining:** the `PostChain` / `PostPass` / OIT framework is a natural hook
point for the DLSS upscale composite and the "UI at native res" pass in Phase 1.

---

## Risk 1b — Vulkan renderer entry points (injection targets) → **FOUND**

The new renderer is **`com.mojang.renderpearl`**, with pluggable backends behind an
API layer:

- `com.mojang.renderpearl.api.device.GpuDevice` / `GpuBackend` — backend-agnostic API.
- `com.mojang.renderpearl.backend.vulkan.*` (41 classes) and `...backend.opengl.*` (63).

Key Vulkan classes and members (names confirmed via constant pool):

| Class | Relevant members | Use to us |
|-------|-------------------|-----------|
| `backend.vulkan.VulkanBackend` | `createDevice`, calls `vkCreateDevice`, `setWindowHints`, `glfwWindowHint`, `handleWindowCreationErrors` | Owns device + window creation; the place to inject required VK extensions/features before `vkCreateDevice`. |
| `backend.vulkan.VulkanDevice` | field `vkDevice : org.lwjgl.vulkan.VkDevice` (+ getter `()Lorg/lwjgl/vulkan/VkDevice;`); `graphicsQueue`/`computeQueue`/`transferQueue : VulkanQueue`; `graphicsQueueFamilyAndIndex` etc.; ctor `(VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long, CheckpointExtension)` | **Primary handle-capture target.** `@Accessor` the fields, `@Inject` at `<init>` TAIL to publish handles. |
| `backend.vulkan.VulkanQueue` | `fetchVkQueue() : org.lwjgl.vulkan.VkQueue`; `queueFamilyIndex` | Raw `VkQueue` for Streamline. |
| `backend.vulkan.VulkanInstance` / `init.FeatureSet` / `init.VulkanFeature` / `init.VulkanPNextStruct` | feature/extension config | Inject DLSS-required instance/device extensions + `pNext` features. |
| `backend.vulkan.checkpoints.NvidiaCheckpointExtension` | (exists) | NV-aware paths already present → some NV device extensions may already be enabled; check before adding. |

LWJGL's `VkDevice` / `VkQueue` expose `.address()` → the raw native handle Streamline
needs, so no JNI of our own is required to hand pointers to SL.

**Jitter target:** `net.minecraft.client.renderer.GameRenderer` exposes projection via
`ProjectionMatrixBuffer`, a `net.minecraft.client.renderer.Projection` type, and
methods returning `org.joml.Matrix4f`; the projection is uploaded as a
`com.mojang.renderpearl.api.buffers.GpuBufferSlice`. Fields seen:
`levelProjectionMatrixBuffer`, `hud3dProjectionMatrixBuffer`, `getFov`,
`getViewRotationProjectionMatrix`. Jitter = post-multiply a sub-pixel translation
into the level projection matrix before it is written to the buffer. *(Exact method
descriptor to confirm via genSources.)*

---

## Risk 2 — Can Streamline hook a game-owned Vulkan device? → **YES (manual hooking)**

NVIDIA Streamline supports two integration modes:

1. **Automatic / interposer** — link `sl.interposer` instead of `vulkan-1`; SL proxies
   `vkCreateInstance` / `vkCreateDevice` and silently adds the extensions, features,
   and queues DLSS needs.
2. **Manual hooking** — the application owns device creation; only the Vulkan entry
   points SL actually needs are routed through it, via `slGetNativeInterface` /
   `slUpgradeInterface`. `slSetVulkanInfo` is **mandatory** in exactly the case where
   `vkCreateInstance` / `vkCreateDevice` are **not** handled by SL.

Minecraft owns device creation (`VulkanBackend.createDevice` → `vkCreateDevice`), so
**manual hooking is our path** and Streamline does **not** need to own creation.

**New sub-task this surfaces:** in manual mode we must add the instance/device
**extensions + features** DLSS requires ourselves (automatic mode adds them behind the
scenes). Injection points: `VulkanBackend` / `VulkanInstance` / `FeatureSet` /
`VulkanFeature` / `VulkanPNextStruct`. This converts R2 from "unknown blocker" to a
"feasible, scoped task."

Sources: NVIDIA-RTX/Streamline `ProgrammingGuideManualHooking.md`,
`ProgrammingGuide.md`; `nvpro-samples/vk_streamline`.

---

## Risk 3 — Streamline / DLSS redistribution license → **NEEDS HUMAN LEGAL READ**

- The Streamline **framework** is open-source (NVIDIA-RTX/Streamline on GitHub).
- The **DLSS feature DLLs and SL binary artifacts are NOT in the repo**; they ship in
  release zips under **NVIDIA's SDK license/EULA**, separate from the repo's OSS license.
- Community mods **do** redistribute Streamline/DLSS DLLs in practice (e.g. a
  DLSS-4.5 Streamline update mod on Nexus Mods, v2.12.0 as of Jun 2026) — but that is
  community behavior, **not** confirmation that NVIDIA's terms permit it.
- The exact redistribution clause was **not** verifiable from public search.

**Action:** a human must read the EULA bundled in the downloaded Streamline release
zip before any distribution. Engineering (dev/personal use) can proceed in parallel;
shipping is gated.

Sources: NVIDIA Streamline developer page; NVIDIA SDK EULA; Streamline GitHub
releases; Nexus Mods (community redistribution evidence).

---

## Estimate impact vs. the brief

- **Motion vectors:** unchanged — full cost confirmed (no infra to reuse).
- **Streamline wiring:** path clarified (manual hooking); risk lowered from "unknown"
  to "feasible," but add the VK extension/feature-injection sub-task.
- **Handle capture / jitter:** targets identified precisely; low risk.
- **License:** unchanged as a shipping gate; does not block engineering.

## Sources

- [Streamline — Manual Hooking guide](https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideManualHooking.md)
- [Streamline — Programming Guide](https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuide.md)
- [nvpro-samples/vk_streamline](https://github.com/nvpro-samples/vk_streamline)
- [NVIDIA-RTX/Streamline](https://github.com/NVIDIA-RTX/Streamline)
- [NVIDIA Streamline developer page](https://developer.nvidia.com/rtx/streamline)
- [NVIDIA SDK EULA](https://docs.nvidia.com/cuda/eula/index.html)
- [Streamline releases](https://github.com/NVIDIA-RTX/Streamline/releases)
- [Games using NVIDIA Streamline (SteamDB)](https://steamdb.info/tech/SDK/NVIDIA_Streamline/)
