# DLSSmc — Project Tracker

> **Single source of truth for status.** Update this file as work progresses so any
> future session can resume without re-deriving context. See [`PRD.md`](./PRD.md) for
> goals/scope and [`SPIKE_FINDINGS.md`](./SPIKE_FINDINGS.md) for the evidence behind
> decisions.

**Pinned target:** `26.3-snapshot-3` · **Toolchain:** JDK 25, Loader 0.19.3, Loom
1.17-SNAPSHOT, Fabric API 0.154.3+26.3 · **Last updated:** 2026-07-10

---

## ▶ RESUME HERE (where we left off)

> **Verify loop:** [`VERIFY.md`](./VERIFY.md) — but note: javap Gates A/A2/A3/A4 are
> **OBSOLETE**. Claude reads the decompiled `-sources.jar` in `.gradle/loom-cache`
> directly. Human is needed only for Gate B (build) and Gates C/D (run/screenshot).

**STATUS (2026-07-11, later): P2-1/P2-2 RUNTIME-VERIFIED — DLSS reported SUPPORTED,
NGX + DLSS plugin fully initialized against Minecraft's own Vulkan device. NEXT: P2-3 —
per-frame plumbing: slGetNewFrameToken + slSetConstants (jitter from DlssJitter, MVs
scale, reset flag stub) + slSetTagForFrame (color=level target LOW-RES pre-upscale,
depth **pre-hand-clear at LevelRenderer.render RETURN**, MV=DlssVelocity target,
output=native target) + slEvaluateFeature(kFeatureDLSS) replacing the NEAREST blit in
the P1-5 upscale path; slDLSSSetOptions via slGetFeatureFunction. Needs VkCommandBuffer
capture (P1-3 — still open) at the blit site. Then P2-4 reset flag, P2-5 tuning.
Original step-1 notes:**
`SlBridge` (FFM/Panama binding of `sl.interposer.dll`) + `VulkanInstanceMixin` /
`VulkanBackendMixin` redirect Mojang's `vkCreateInstance`/`vkCreateDevice` through SL's
creation proxies (P2-1 collapsed into P2-2 as planned — SL adds extensions/features/
queues itself; no `slSetVulkanInfo` needed). slInit runs lazily inside the
vkCreateInstance redirect (robust vs loader ordering). `/dlssmc sl` prints SL status;
after device creation the log shows `slIsFeatureSupported(kFeatureDLSS)`. Loom client
run now passes `--enable-native-access=ALL-UNNAMED` (FFM restricted methods, JDK 25).
@Redirect targets verified against constant pool of the real class files (owner VK12,
descriptors exact). Swapchain/present hooks in sl_hooks.h are DLSS-G-only → skipped
(DLSS-SR scope). ABI offsets for sl::Preferences/AdapterInfo documented in SlBridge
javadoc. Streamline SDK v2.12.0 extracted at repo root (gitignored — NVIDIA license).**

- **M1/M2 (earlier):** Vulkan device+queue captured (S1); Halton jitter on the world
  projection (S2); resolution decoupling — world at `DlssResolution.scale` (default
  0.5) upscaled to native, HUD crisp (S3). `/dlssmc scale` cycles the render scale.
- **M3 — motion vectors (S4/S5):** two-layer architecture, all runtime-verified:
  1. *Fullscreen camera fallback* (`DlssVelocity` + `dlss_velocity.fsh`): whole
     reprojection folded into ONE matrix (`DlssMotion.reprojectionMatrix()`), uploaded
     via Mojang's `ProjectionMatrixBuffer` + stock PROJECTION layout. Covers sky /
     entities / translucents. Assumed depth 0.0002 (REVERSE-Z: small z = far).
  2. *Terrain prepass* (`DlssTerrainVelocity` + `terrain_velocity.vsh/.fsh` +
     `LevelRendererMixin`): replays Mojang's `ChunkSectionsToRender` OPAQUE draws into
     the RG16F velocity target with a TERRAIN_SNIPPET-derived pipeline, custom
     `DlssReprojection` UBO, level depth attached GEQUAL/no-write (+2 bias). Exact
     per-pixel terrain MVs with correct occlusion.
  Debug overlay: `/dlssmc mv` — log-scaled tint alpha-blended over the finished frame.
- **Hard-won renderer facts (do not relearn):** renderer is REVERSE-Z
  (`DepthStencilState.DEFAULT` = GEQUAL; NDC z ≈ near/dist). `GameRenderer.renderLevel`
  switches to the 3D-HUD projection AND **clears the level depth to far** before
  `renderItemInHand` — so anything needing world depth (velocity prepass; the P2-3
  DLSS depth tag!) must run at `LevelRenderer.render` RETURN, not renderLevel RETURN.
  Uniform state must be SNAPSHOTTED at executeSolid time for replays. Debug overlays
  must alpha-blend over the backbuffer, never re-draw the scene from the level texture.
- **Deferred:** P1-8 entity MVs (do after DLSS is live; ghosting will show how much
  they matter), P1-9 particles/water, water/translucent velocity.
- **NEXT — Phase 2 (M4):** Streamline SDK docs/headers are readable in-repo
  (`streamline-sdk-v2.12.0/`). Key discovery: SL's **manual-hooking proxies** for
  `vkCreateInstance`/`vkCreateDevice` add all required extensions/features/queues
  automatically → P2-1 collapses into P2-2. Plan: (1) FFM (Panama) bridge loading
  `sl.interposer.dll`; `slInit` at client init (before renderer start); (2) mixin
  redirect of Mojang's instance/device creation through SL proxies (fallback: full
  manual via `slGetFeatureRequirements` + `slSetVulkanInfo` with handles from
  `DlssRenderState`); (3) per-frame tags (P2-3): color = level target, depth =
  **pre-hand-clear**, MV = `DlssVelocity.velocityTarget()`, jitter =
  `DlssJitter.pixelOffsetX/Y()`; replace the NEAREST upscale blit with slEvaluate.
  Human: read `streamline-sdk-v2.12.0/license.txt` (P2-6, ships gate).

---

## Milestones

- [x] **M0 — Environment & recon** — template scaffolded, snapshot jar resolved, risks spiked.
- [x] **M1 — Handles + jitter** — device+queue captured (S1) and jitter applied with correct Halton offsets (S2), both runtime-verified 2026-07-10.
- [x] **M2 — Resolution decoupling** — VISUALLY CONFIRMED (S3): half-res world upscaled to native, HUD crisp (2026-07-10).
- [x] **M3 — Motion vectors (terrain)** — VERIFIED 2026-07-10: exact per-pixel terrain MVs (prepass) + camera fallback (sky/entities/translucents); overlay-verified incl. occlusion. Entity MVs deferred to P1-8 (post-DLSS, ghosting-guided). Gate-D bug hunt fixed: reverse-Z assumed depth, hand-FOV projection displacement, overlay scene re-draw ghosting, and the renderItemInHand depth-clear (velocity passes must run at LevelRenderer.render RETURN; **P2-3 note: DLSS depth tag must also be grabbed pre-clear**).
- [ ] **M4 — DLSS on** — Streamline manual-hooked; DLSS SR upscaling live.
- [ ] **M5 — Quality bar** — no gross ghosting; reset flag correct; prototype demo.

---

## Workstreams & tasks

Status: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked/needs human

### Phase 0 — Setup & spikes  *(complete)*
- [x] P0-1 Scaffold Fabric template (dlssmc / com.jhp / 26.3-snapshot-3).
- [x] P0-2 Resolve snapshot jar via Loom; confirm 3,551 client classes readable.
- [x] P0-3 Spike R1 (motion/TAA/jitter) → none present.
- [x] P0-4 Spike R1b (Vulkan entry points) → targets identified.
- [x] P0-5 Spike R2 (Streamline device hooking) → manual hooking viable.
- [x] P0-6 Spike R3 (license) → needs human read; not an engineering blocker.
- [x] P0-7 Git baseline + docs.

### Phase 1 — Rendering rework (mod side)
- [x] P1-1 Vulkan handle-capture (`VulkanDeviceMixin` + `DlssRenderState`): **runtime-confirmed (S1)** — device=0x2197..880, graphicsQueue=0x2197..cd0, family=0 on NVIDIA 596.49 / Vulkan 1.4. Mixin now `require=1`.
- [x] P1-2 Descriptors confirmed via javap + **runtime capture verified (S1)** on real hardware.
- [ ] P1-3 Capture swapchain images + per-frame command buffer(s) needed for SL tagging.
- [x] P1-4 Jitter **runtime-verified on the WORLD projection (S2)**: `@ModifyArg` on `getBuffer(Matrix4f)` fires; `jitter applied to WORLD projection dims=854x480 ndc=(-5.85e-4,6.94e-4)`, no injection errors. `ProjectionMixin` (HUD) retired.
- [x] P1-5 **Resolution decoupling VISUALLY CONFIRMED (S3)**: at scale=0.5 the 3D world renders blocky/half-res and upscales to native; HUD crisp. Field-swap of `mainRenderTarget` redirects the whole world render graph as predicted.
- [x] P1-6 HUD/UI at native res — satisfied by the P1-5 design: world upscales into the native target at `renderLevel` RETURN, then vanilla GUI renders into that native target. Confirmed crisp HUD over half-res world.
- [x] P1-7 Motion vectors — DONE (see M3 in RESUME); originally: reprojection math CPU-verified; custom-shader **pipeline plumbing confirmed** (UV gradient). **BLOCKER found:** renderpearl does not expose the depth buffer for direct `sampler2D` sampling — Mojang only uses `depthTextureView` as a *depth attachment*; the sole sampled "depth" is a *color* depth-bounds target. A screen-space camera-reprojection MV pass needs per-pixel depth, so it requires either:
  - (A) a **depth-resolve pass** that writes scene depth into a color/R32F target (then sample that), or
  - (B) **MRT velocity output** written during the geometry pass (chunk/entity pipelines emit velocity to a 2nd color attachment) — the DLSS-canonical way, but invasive across many pipelines.
  This is the biggest, most iteration-heavy Phase-1 item (as the brief predicted).
  **DECIDED 2026-07-10: Approach B**, staged — slice 1 (camera-only fullscreen velocity, exact for rotation, coded, awaiting Gate A2/B) → slice 2 (MRT terrain) → P1-8 (entities).
- [ ] P1-8 Motion vectors: per-object for entities (previous-frame model transforms).
- [ ] P1-9 Motion vectors: chunks/terrain (mostly camera-only) + particles/water passes as needed.
- [x] P1-10 Depth: RESOLVED — renderer is REVERSE-Z (GEQUAL default, NDC z≈near/dist), `D32_FLOAT`. The old "depth samples as 0" finding was (at least partly) the renderItemInHand depth-CLEAR: GameRenderer wipes level depth to far (0.0) mid-renderLevel. Depth IS usable as an attachment pre-clear (the prepass proves it). For P2-3 the DLSS depth tag must be taken at LevelRenderer.render RETURN.

### Phase 2 — DLSS integration
- [x] P2-1 ~~Inject extensions/features manually~~ — COLLAPSED into P2-2: SL's `vkCreateInstance`/`vkCreateDevice` proxies add all required extensions, features and queues (ManualHooking guide §4.2).
- [x] P2-2 Streamline manual hooking — **RUNTIME-VERIFIED 2026-07-11**: `slIsFeatureSupported(kFeatureDLSS): SUPPORTED` on RTX 4070 SUPER / driver 596.49 / VK 1.4.329; device created via SL proxy (0x17769ac0b40, same handle captured by VulkanDeviceMixin); NGX loaded nvngx_dlss.dll v310.7.0 and set DLSS cubins (arch 0x190); game runs normally on Vulkan backend. Fix history: (a) iteration-1 crash root-caused below; (b) invokeExact ternary→Object WrongMethodTypeException in the enum bridge (typed local fixes it); (c) markChainBroken() guard added so partial proxy fallback can't recreate the crash. Note for P2-3: SL warns Vulkan hooks CmdBindPipeline/CmdBindDescriptorSets/BeginCommandBuffer "NOT supported" — expected with eDisableCLStateTracking, revisit only if slEvaluateFeature misbehaves. NGX used default app ID 100721531 + our projectId (cms id 876232c). Iteration-1 crash post-mortem: EXCEPTION_ACCESS_VIOLATION at pc=0x0 inside SL's vkCreateDevice, *after* extension merge. Cause (verified in SDK source `source/core/sl.interposer/vulkan/wrapper.cpp`): SL's post-create path does `s_vk.instance = instanceDeviceMap[physicalDevice]` (line 940); that map is only filled by SL's `vkEnumeratePhysicalDevices` hook (line 982), which Mojang bypassed → null instance → dispatch rebuilt from `vkGetInstanceProcAddr(NULL,…)` → plugin init calls null fn ptr. FIX: redirect `VK12.vkEnumeratePhysicalDevices` in `VulkanBackend.findPhysicalDevice` through the interposer too, and gate the whole proxied chain on `SlBridge.isInstanceProxied()` (all-or-nothing). LESSON: when using SL's creation proxies, EVERY interposer-intercepted entry point Mojang calls must be routed through SL (SL_INTERCEPT list, wrapper.cpp ~2360: GetInstance/DeviceProcAddr, Create/DestroyInstance, Create/DestroyDevice, EnumeratePhysicalDevices, QueuePresent, CreateImage, CmdPipelineBarrier, BeginCommandBuffer, swapchain fns, DeviceWaitIdle). Known residuals, likely fine for SR but check if weirdness: vkDestroyInstance/vkDestroyDevice not routed (backend restart would leave SL stale), vkCreateImage/vkCmdPipelineBarrier/vkBeginCommandBuffer not routed (may matter for P2-3 tagging). Awaiting Gate C iteration 2.
- [ ] P2-3 Tag per-frame buffers into SL: color (low-res), output, depth, motion vectors, jitter offset, exposure/HDR.
- [ ] P2-4 Fire DLSS **reset flag** on teleport/portal/respawn/dimension change (**S7**).
- [ ] P2-5 Artifact tuning: ghosting on foliage/particles/thin geometry; disocclusion at chunk edges (**S6**).
- [ ] P2-6 **[!] Human:** read NVIDIA Streamline SDK EULA in the release zip; resolve redistribution before shipping (**Risk 3**).

### Verification (ongoing)
- [x] V-1 **BUILD SUCCESSFUL** on the dev machine (JDK 25 toolchain) — all mixins + DlssJitter/DlssRenderState compile against 26.3-snapshot-3 (2026-07-10).
- [x] V-2 Runtime handle-capture confirmed — non-zero device+queue handles logged (2026-07-10).
- [x] V-3 Numeric jitter check — logged offsets match Halton(2,3) and NDC=2·offset/dim exactly (2026-07-10).
- [~] V-4 Custom-shader GPU plumbing **CONFIRMED** (UV gradient renders via a dlssmc pipeline+shader). Depth half was black → raw depth not directly sampleable (see P1-7 finding).
- [ ] V-5 DLSS active + internal-res reduction confirmed.

---

## Injection-target reference (from spike)

| Purpose | Target class | Member / signature note |
|---------|--------------|-------------------------|
| Capture `VkDevice` + queues | `com.mojang.renderpearl.backend.vulkan.VulkanDevice` | field `vkDevice: org.lwjgl.vulkan.VkDevice`; `graphicsQueue/computeQueue/transferQueue: VulkanQueue`; ctor tail `(VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long, CheckpointExtension)` |
| Raw `VkQueue` | `...backend.vulkan.VulkanQueue` | `fetchVkQueue(): org.lwjgl.vulkan.VkQueue`, `queueFamilyIndex` |
| Device/extension creation | `...backend.vulkan.VulkanBackend` | `createDevice`, `vkCreateDevice`, `setWindowHints` |
| VK features/extensions | `...backend.vulkan.init.{FeatureSet, VulkanFeature, VulkanPNextStruct}` | add DLSS-required features/extensions |
| Jitter (apply) | `GameRenderer.renderLevel` → `ProjectionMatrixBuffer.getBuffer(Matrix4f)` | `@ModifyArg` index 0 — jitter the **world** projection Matrix4f (HUD uses `getBuffer(Projection)`, left unjittered) |
| Jitter (phase) | `net.minecraft.client.renderer.GameRenderer` | `renderLevel` HEAD advances Halton phase; RETURN closes window (Phase-2 bookkeeping) |
| Composite / post | `net.minecraft.client.renderer.{PostChain, PostPass, PostChainConfig}` + `...renderer.oit.*` | upscale composite + UI-at-native pass |

Raw native handle for SL = LWJGL `VkDevice.address()` / `VkQueue.address()`.

> ✅ Descriptors confirmed via `javap` **and all three mixins runtime-verified** (Gate C,
> 2026-07-10) — device capture (S1) + jitter (S2). All now `require = 1`.

---

## Decision log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-07-10 | Pin `26.3-snapshot-3`; bump deliberately. | Vulkan internals change weekly (PRD §7). |
| 2026-07-10 | Vulkan backend only; OpenGL out of scope. | DLSS never supported GL; renderer has a clean Vulkan path. |
| 2026-07-11 | P2-1 collapsed into P2-2 via SL creation proxies; swapchain/present hooks skipped. | ManualHooking guide §4.2: proxies add required extensions/features/queues; sl_hooks.h swapchain hooks only matter for Frame Generation (out of scope). |
| 2026-07-11 | slInit lazily inside the vkCreateInstance redirect, not onInitializeClient. | Guaranteed to precede instance creation regardless of mod-loader init order; idempotent for backend restarts. |