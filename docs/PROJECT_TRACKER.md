# DLSSmc — Project Tracker

> **Single source of truth for status.** Update this file as work progresses so any
> future session can resume without re-deriving context. See [`PRD.md`](./PRD.md) for
> goals/scope and [`SPIKE_FINDINGS.md`](./SPIKE_FINDINGS.md) for the evidence behind
> decisions.

**Pinned target:** `26.3-snapshot-3` · **Toolchain:** JDK 25, Loader 0.19.3, Loom
1.17-SNAPSHOT, Fabric API 0.154.3+26.3 · **Last updated:** 2026-07-11

---

## ▶ RESUME HERE (where we left off)

> **Verify loop:** [`VERIFY.md`](./VERIFY.md) — but note: javap Gates A/A2/A3/A4 are
> **OBSOLETE**. Claude reads the decompiled `-sources.jar` in `.gradle/loom-cache`
> directly. Human is needed only for Gate B (build) and Gates C/D (run/screenshot).

**STATUS (2026-07-11, end of session 2): 🎉 M5 COMPLETE — DLSS-SR prototype DONE.
P2-5 verified (noise gone, no ghosting, no pitch issues; mvecScale inversion was the
root cause). P2-4 verified (camera-cut reset). The ONLY remaining pre-ship item is
P2-6: human reads `streamline-sdk-v2.12.0/license.txt` + the redistribution terms for
sl.interposer.dll / nvngx_dlss.dll before publishing the mod (Risk 3). NEXT ENGINEERING
WORK: Phase 3 frame generation — [`FRAMEGEN_BRIEF.md`](./FRAMEGEN_BRIEF.md) read;
**FG-1 swapchain recon COMPLETE 2026-07-11 (findings under Phase 3 below)**; next is
FG-2 **VERIFIED (Gate B+C 2026-07-11: all 4 features incl. DLSS_G report SUPPORTED on 4070S, no SL errors)**. FG-3 swapchain routing **VERIFIED (Gate B+C 2026-07-11)**. FG-4 (shared token + Reflex sleep + 6 PCL markers) **VERIFIED (Gate B+C 2026-07-11)**; FG-2 SlBridge revert also restored. **FG-5 COMPLETE 2026-07-11 — DLSS-G plumbing done & working (enable/controls/state/tags/stable-copies/HUD-less; 2×, status eOk).** OPEN: **MV-Q quality pass** (user-chosen next) — pre-existing DLSS-SR fast-vector noise/smear (reproduces FG-OFF, confirmed by user; MV math byte-identical to M5; FG amplifies it since it warps along MVs). ITER-1: root cause is the DLSS PRESET — `sendOptionsIfNeeded` forced all presets to eDefault (Performance mode -> Preset M); added `/dlssmc preset` live cycle default->K->L->M (transformer K/L are the most motion-stable), awaiting Gate D A/B. Also gated FG-4 machinery (token/ReflexSleep/markers) on fgEnabled so FG-off == M5. Remaining FG stages after MV-Q: FG-6 pacing, FG-7 MFG. — `/dlssmc fg` enables DLSS-G (slDLSSGSetOptions eOn) + prints slDLSSGGetState; depth/MV tagged eValidUntilPresent when on. This is the diagnostic-first increment: the state readout tells us whether FG-5b (stable copies + HUD-less color) is needed. After Gate D: FG-5b hardening, then FG-6 pacing.
The P3 backlog and staged FG-1…FG-7 plan (API GUIDs/offsets, SL_INTERCEPT list) live in
the brief. Commands: /dlssmc dlss | sl | mv | scale | mode |
mvx | mvy | jx | jy.**
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
- [x] **M4 — DLSS on** — **VERIFIED 2026-07-11**: Streamline manual-hooked via creation proxies; DLSS-SR upscaling live in-game (1280x685 → 2560x1369, MaxPerformance) on RTX 4070 SUPER / driver 596.49. Quality bar (ghosting/artifact tuning) is M5.
- [x] **M5 — Quality bar** — **COMPLETE 2026-07-11**: no ghosting/noise/pitch artifacts (P2-5, mvecScale fix), reset flag on camera cuts (P2-4), DLSS-SR prototype fully playable.
- [~] **M6 — Frame generation (DLSS-G)** — **FG LIVE 2026-07-11** (2× on 4070S, status eOk, Reflex+PCL markers, SL-owned swapchain, stable-copy tags). Remaining: MV-quality pass (fast-vector smear, pre-existing), FG-6 pacing, FG-7 MFG. Original: FG live with Reflex; vsync mutually exclusive with FG; fps cap applies to RENDERED frames (presented = cap × FG factor); MFG pass-through (50-series only, untestable on dev HW).

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
- [x] P2-3 **RUNTIME-VERIFIED 2026-07-11**: `[DLSSmc] DLSS evaluate LIVE: 1280x685 -> 2560x1369` (MaxPerformance), per-frame evaluate running with no SL errors; visually confirmed upscaling (user-verified; known artifacts deferred to P2-5). One Gate-C iteration: slSetTagForFrame returned 19 (eErrorInvalidIntegration) because the `eUseFrameBasedResourceTagging` preference flag (1<<7) wasn't set at slInit — SL hard-requires it for the frame-based tagging API (sl.cpp). Design notes below still accurate: `DlssEvaluator` records token→slSetConstants→slSetTagForFrame→slEvaluateFeature→vkCmdCopyImage at **LevelRenderer.render RETURN** (depth intact, velocity just written) into a transient cmd buffer via public `VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()`/`execute()` (execute closes Mojang's open buffer first → ordering world→eval→hand/HUD is exact; P1-3 resolved with zero mixins). Facts baked in: renderpearl keeps ALL images in VK_IMAGE_LAYOUT_GENERAL forever (Resource.state=1 always); RenderTarget textures are usage 15 (SAMPLED+ATTACH+COPY src/dst) so level color/depth + MV tag directly; renderpearl has NO STORAGE usage bit → DLSS output is our own raw LWJGL-created RGBA8 STORAGE VkImage, copied into native color after eval. Constants: clipToPrevClip = DlssMotion.reprojectionMatrix() verbatim; JOML column-major raw = SL row-major transpose (write as-is); depthInverted=true (REVERSE-Z), cameraMotionIncluded=true, MVs unjittered/undilated, mvecScale={renderW,renderH} (buffer is UV-space cur→prev — sign/scale is the P2-5 knob); reset wired to DlssJitter.requestReset (P2-4 hook exists). Early-restore design: on eval success the main target is restored BEFORE renderItemInHand (duck iface `DlssTargetAccess` on GameRendererMixin) → hand+HUD at native res over DLSS output, depth-clear hazard gone, NEAREST blit auto-skipped (savedTarget nulled). useAutoExposure=eTrue (no exposure tag; LDR colorBuffersHDR=eFalse). Failure latches `broken` → NEAREST blit fallback; `/dlssmc dlss` toggles + clears latch.
- [x] P2-4 Reset flag — **DONE, user-verified 2026-07-11**: camera-cut detector in `DlssMotion.capture` (level identity swap or >8-block/frame jump → requestReset + re-prime, zero MVs on cut frame) (**S7**).
- [ ] **KNOWN ISSUE (pre-existing, NOT FG-3), found during FG-3 Gate C:** switching DLSS
  mode to **DLAA** (1:1) makes NGX evaluate spam `0xbad00005` — DLAA's context expects
  native-res input but the world still renders at `DlssResolution.scale` (<1), so the color
  tag extents mismatch the DLAA DLSSContext. Self-corrects on leaving DLAA. Fix (P2-5 area,
  deferred): force `scale=1.0` (render native) whenever mode==DLAA, or hide DLAA from the
  mode cycle. Does not affect FG work.
- [x] P2-5 Artifact tuning — **COMPLETE 2026-07-11**: translation noise solved (mvecScale inversion); user-verified no entity ghosting and no pitch issues at defaults (mvSign=(1,-1), jitterSign=(1,1), auto mode). P1-8 entity MVs not needed for the quality bar.
- [ ] P2-6 **[!] Human:** read NVIDIA Streamline SDK EULA in the release zip; resolve redistribution before shipping (**Risk 3**).

### Phase 3 — Frame generation (DLSS-G / DLSS-MFG)  *(backlog, defined 2026-07-11)*

> Sources: `streamline-sdk-v2.12.0/docs/ProgrammingGuideDLSS_G.md` (+ Reflex guide).
> Same discipline as Phase 2: SL interposer routing is ALL-OR-NOTHING (P2-2 lesson).

**FG-1 SWAPCHAIN RECON — COMPLETE 2026-07-11 (read from decompiled `-sources.jar`).**

- **Swapchain owner = ONE class:** `com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface`
  (implements `GpuSurfaceBackend`). Holds ALL swapchain state: `long swapchain`,
  `LongList swapchainImages` (RAW VkImage handles — NOT wrapped in renderpearl
  textures), `swapchainImageFormat` (picks colorSpace 0 + VkFormat 37 or 44),
  `swapchainWidth/Height`, `acquireSemaphores[2]` (ping-pong) + `presentSemaphores[per
  image]`, `VkQueue presentQueue = device.graphicsQueue().vkQueue()`, `long surface`.
  This is the ONLY FG-3 mixin target.
- **The 5 KHR call sites (all in VulkanGpuSurface, via `org.lwjgl.vulkan.KHRSwapchain.*`
  static; device arg = `this.device.vkDevice()` except present = `this.presentQueue`):**
  - `configure(GpuSurface.Configuration)`: `vkCreateSwapchainKHR` @222;
    `vkGetSwapchainImagesKHR` @225 (count) + @229 (images); calls `destroySwapchain()`
    at head. minImageCount=max(3,caps.min); imageUsage=2 (TRANSFER_DST).
  - `destroySwapchain()`: `vkDestroySwapchainKHR` @166 (graphicsQueue.waitIdle() first);
    called by `configure()` head AND `close()`.
  - `acquireNextTexture()`: `vkAcquireNextImageKHR` @280 (5 s timeout, binary
    acquireSemaphore); handles OUT_OF_DATE(-1000001004)/SUBOPTIMAL(1000001003).
  - `present()`: `vkQueuePresentKHR` @413 on presentQueue (waits presentSemaphores[img]).
- **Resize/recreate driver = `Minecraft.renderFrame(boolean advanceGameTime)` @1279.**
  If `windowSurfaceNeedsReconfiguring || (isSuboptimal && !surfaceIsInvalid)`: reads GLFW
  framebuffer size → builds `GpuSurface.Configuration(w,h,presentMode)` →
  `windowSurface.configure(config)` @1300 (destroy+create). `invalidateSurfaceConfiguration()`
  @3020 sets the flag; triggered by the vsync option (`Options.java:506`), window resize,
  and @1496. **Per-frame order in renderFrame:** configure(if needed) → `acquireNextTexture`
  @1313 → [render] → `blitFromTexture(mainRenderTarget color)` @1392 → submit → `present`
  @1408 → FramerateLimiter @1420.
- **Present-mode / vsync selection:** `GpuSurface.PresentMode.getSupportedVsyncMode(
  supported, options.enableVsync().get())` @Minecraft:1294. vsync ON → prefer
  {FIFO_RELAXED, FIFO}; OFF → {IMMEDIATE, MAILBOX, FIFO}; → `VulkanConst.toVk()` →VK enum
  @VulkanGpuSurface:208. **FG-6 hook:** force vsync off by `options.enableVsync().set(false)`
  + `invalidateSurfaceConfiguration()` (restore prior value on FG off). NOTE:
  `Minecraft.java:945` already does `enableVsync().set(false)` in one path — check it.
- **Image wrapping:** final frame color is blitted (`vkCmdBlitImage`, Y-flip via offsets)
  from `gameRenderer.mainRenderTarget().getColorTextureView()` into the acquired raw
  swapchain image (`blitFromTexture` @VulkanGpuSurface:302) — the SAME native target our
  DLSS-SR output already lands in.
- **FPS limiter = `net.minecraft.client.FramerateLimiter.limitDisplayFPS(int)`** — called
  in `renderFrame` @1420 AFTER `present()`, only if `framerateLimit < 260` (value from
  `FramerateLimitTracker.getFramerateLimit()` @1344 → `gameRenderState().framerateLimit`).
  Mechanism: `LockSupport.parkNanos`/`Thread.onSpinWait` busy-wait on the render thread to
  hit target frame time. **Confirms FG-6 policy is sound:** the limiter throttles the real
  game loop and runs AFTER present, so it caps RENDERED frames and never sees SL's
  generated presents (those are injected inside SL's replacement `vkQueuePresentKHR`).
- **Frame-loop entry for FG-4 markers:** `Minecraft.runTick(boolean)` @1154 drives
  renderFrame + sets framerateLimit @1344; `Minecraft.tick()` @1845 = 20 Hz sim. Marker
  placement: `eSimulationStart/End` around tick, `eRenderSubmitStart/End` around
  GameRenderer.render, `ePresentStart/End` around `present()`@1408, `slReflexSleep` at
  runTick start.

- [x] P3-1 **Swapchain/present routing** — **VERIFIED 2026-07-11 (Gate B+C).** `VulkanGpuSurfaceMixin` routes all 5 KHR sites through SL; clean ~89s session with resize/F11/alt-tab + DLSS mode cycling: no crash, no `PROXY CHAIN BROKEN`, no swapchain errors; SL plugin added `VK_KHR_swapchain`; DLSS-SR stayed live. FG-1 recon findings above. New `VulkanGpuSurfaceMixin` @Redirects all 5 KHR sites (create/get-images/acquire/present/destroy) through `SlBridge` interposer wrappers, all-or-nothing gated on `isInstanceProxied()`, vanilla+`markChainBroken` fallback on throw. FG stays OFF this stage (SL forwards to driver); Gate C must show identical behaviour incl. resize/F11 + DLSS-SR still live. RUNTIME WATCH: present now routes via SL every frame while DLSS-SR evaluate (P2-3) is active — verify no interference. Target=`VulkanGpuSurface`, 5 KHR sites mapped. The piece deliberately skipped in P2-2: mixin-redirect ALL of Mojang's `vkCreateSwapchainKHR` / `vkGetSwapchainImagesKHR` / `vkAcquireNextImageKHR` / `vkQueuePresentKHR` / destroy + the resize-recreate path through SL's proxies (FG injects generated frames at present via its replacement swapchain). Biggest risk item; expect P2-2-style crash iterations, esp. on resize/F11.
- [x] P3-2 **slInit features** — **VERIFIED 2026-07-11 (Gate B+C): all four `slIsFeatureSupported` report SUPPORTED (DLSS, DLSS_G, Reflex, PCL) on RTX 4070 SUPER; no SL init errors, DLSS-SR unaffected.** `SlBridge.buildPreferences` now loads `{DLSS, Reflex(3), PCL(4), DLSS_G(1000)}` (numFeatures=4); `logDlssSupport` checks all four via `slIsFeatureSupported` so Gate C confirms DLSS-G on the 4070S. Original: `featuresToLoad += kFeatureDLSS_G, kFeatureReflex, kFeaturePCL` (lazy slInit already precedes instance creation; proxies then add FG's extra device extensions/queues automatically).
- [x] P3-3 **Reflex + PCL markers** — **VERIFIED 2026-07-11 (Gate B+C):** all 4 features SUPPORTED (FG-2 restored), `slReflexSetOptions(eLowLatency)=0`, and ZERO `common constants cannot be found for frame N` over a multi-minute session with resizes + mode cycling → shared token + 6 markers are frame-coherent. DLSS-SR toggles fine. (Only log errors = the known DLAA 0xbad00005 transient, recovers.) `MinecraftMixin` (runTick/renderFrame HEAD) + `VulkanGpuSurfaceMixin` present bracket emit the 6 PCL markers; ONE shared per-frame token in `SlBridge` (`frameBegin`/`currentFrameToken`/`mark`) used by markers+constants+evaluate (DlssEvaluator now reuses it); `slReflexSetOptions(eLowLatency)` once + `slReflexSleep` per frame; all resolved via `slGetFeatureFunction` (`resolveFeatureFn`). ALSO restored FG-2 SlBridge changes that the FG-3 commit had accidentally reverted (wrong git base). HARD requirement (FG refuses to run without): per-frame `eSimulationStart/End`, `eRenderSubmitStart/End`, `ePresentStart/End` markers whose frame indices match the Constants frame token EXACTLY (guide: "common constants cannot be found for frame N" = marker/token desync). Needs mixins in Minecraft's frame loop — new territory outside the render graph.
- [x] P3-4 **Present-lifecycle tags** — **DONE 2026-07-11 (FG-5b).** 3 dedicated CopyImages (depth D32/mvec RG16F render-res, HUD-less RGBA8 native-res); per-frame vkCmdCopyImage snapshots + eValidUntilPresent tags (5 slots, HUD-less=type2); FG-off path unchanged. FG generates at 2×, status eOk. Detail: DlssEvaluator now allocates 3 dedicated device-local images (`CopyImage`): depth D32 + mvec RG16F at render res, HUD-less RGBA8 at native res. When FG on, the evaluate cmd buffer `vkCmdCopyImage`s live depth+velocity into the copies (before evaluate, so SR reads them too) and native→HUD-less (after copyOutputToNative, pre hand/HUD), with global barriers; tags expanded to 5 slots — depth/MV/HUD-less tagged `eValidUntilPresent`, HUD-less = kBufferTypeHUDLessColor(2). Zero overhead when FG off (numTags=4, no copies). Should kill the fast-motion smearing. NOTE: single (not double-buffered) copies + no inputsProcessingCompletionFence wait yet — if residual tearing under load, that's a follow-up. EARLIER: Motion smearing on fast movement with FG on = predicted torn-MV issue: FG-5a tags the LIVE velocity/depth buffers eValidUntilPresent, but FG consumes them async at PRESENT (Thread-1) while the next frame's render pass overwrites them. FIX (FG-5b): dedicated stable-copy images (depth D32 + mvec RG16F, render-res) filled via vkCmdCopyImage each frame before tagging the COPIES eValidUntilPresent. HUD-less color still deferred (v1 uses backbuffer). ORIGINAL FG-5a: DlssEvaluator now flips depth+MV tag lifecycle to `eValidUntilPresent`(1) when FG is on (else `eValidUntilEvaluate`). DEFERRED to FG-5b pending the Gate-D state readout: dedicated STABLE COPIES of depth/MV (races if the game overwrites velocity while FG reads it across frames) and the HUD-less color tag (v1 uses the swapchain backbuffer → HUD shimmer on generated frames, accepted). ORIGINAL: — depth + MV currently `eValidUntilEvaluate`; FG consumes at PRESENT time → stable copies tagged `eValidUntilPresent` (velocity target is rewritten next frame). Tag **HUD-less color** (critical for quality per guide §5.2) — we already have the exact buffer: native target right after the DLSS copy, before hand/HUD (early-restore point) — one extra copy. UI-Alpha deferred: MC has no HUD alpha buffer; proper fix = render HUD offscreen RGBA + composite; v1 accepts HUD shimmer on generated frames.
- [x] P3-5 **Options + controls** — **VERIFIED 2026-07-11 (Gate D): FG TURNS ON.** `/dlssmc fg` → `slDLSSGSetOptions(eOn)=0`, readout `status=0x0 (eOk), maxGen=1, minWH=100`; SL log `DLSS-G interpolation state changed disabled→enabled, numFramesToGenerate=1` — actually generating frames, 2× (F3 real-fps halves = expected). `Multi-frame not supported, max 1` on 4070S (FG-7). No crash/chain-broken. Original FG-5a: `SlBridge.setFrameGeneration(on)` (slDLSSGSetOptions eOn/eOff, DLSSGOptions v5 @32 mode/@36 numFrames) + `frameGenState()` (slDLSSGGetState v4: status+decoded fail flags, numFramesToGenerateMax, minWH, presentedSinceLast, ~VRAM) + `/dlssmc fg` toggle+readout. ORIGINAL: — `slDLSSGSetOptions`/`slDLSSGGetState`, `/dlssmc fg` toggle + readout (status, fail reasons, VRAM estimate, actual presented fps).
- [ ] P3-6 **Frame pacing policy (DECIDED 2026-07-11)** — (a) vsync and FG are MUTUALLY EXCLUSIVE: enabling FG forces vsync off (restore user setting when FG turns off); (b) the fps limit caps RENDERED (real) frames only — FG multiplies presentation ABOVE the cap (e.g. cap 60 + 2× FG = 120 presented). Verify Mojang's limiter throttles the game loop (real frames) and never sees generated presents; note F3 fps shows real frames — presented fps via `/dlssmc fg` readout (P3-5).
- [ ] P3-7 **Multi-frame generation** — `numFramesToGenerate` up to 3 (4×) / `DLSSGMode::eDynamic` pass-through; hardware-gated to RTX 50-series — `slDLSSGGetState` caps query decides UI; UNTESTABLE on dev 4070 SUPER (ship as experimental).

### Verification (ongoing)
- [x] V-1 **BUILD SUCCESSFUL** on the dev machine (JDK 25 toolchain) — all mixins + DlssJitter/DlssRenderState compile against 26.3-snapshot-3 (2026-07-10).
- [x] V-2 Runtime handle-capture confirmed — non-zero device+queue handles logged (2026-07-10).
- [x] V-3 Numeric jitter check — logged offsets match Halton(2,3) and NDC=2·offset/dim exactly (2026-07-10).
- [~] V-4 Custom-shader GPU plumbing **CONFIRMED** (UV gradient renders via a dlssmc pipeline+shader). Depth half was black → raw depth not directly sampleable (see P1-7 finding).
- [x] V-5 **DLSS active + internal-res reduction CONFIRMED 2026-07-11**: evaluate runs per-frame at 1280x685 → 2560x1369; slDLSSSetOptions OK (MaxPerformance); output via our STORAGE image copied into the native target; hand+HUD native-res via early restore.

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
| 2026-07-11 | FG-3 commit had reverted FG-2's SlBridge edits (based on stale mounted-.git HEAD, not the off-mount commit); FG-4 restored them. | The in-sandbox mounted `.git` lags the off-mount commit repo; ALWAYS base file rewrites on the off-mount repo HEAD (`/tmp/dlssmc*.git`), not `git show HEAD` of the mount. FG-3 swapchain test was still valid (routing is feature-independent) but ran with only kFeatureDLSS loaded. |
| 2026-07-11 | FG pacing: vsync mutually exclusive with FG; fps cap applies to rendered frames only (presented = cap × FG factor). | User decision. Vsync fights FG pacing (Reflex owns it); cap-on-real-frames preserves the intuitive "60 cap → 120 presented at 2×" behavior. |
