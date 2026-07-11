# FRAMEGEN_BRIEF — Phase 3 implementation spec (DLSS-G / MFG)

> Self-contained brief for the implementing session. Read this + `PROJECT_TRACKER.md`
> RESUME before writing any code. Written 2026-07-11 after M5 (DLSS-SR) completed.

## 0. Mission & hardware

Add DLSS Frame Generation to dlssmc on top of the working DLSS-SR integration.
Dev HW: RTX 4070 SUPER, driver 596.49 → FG **2×** is testable; **MFG (3×/4×) is NOT**
(50-series only — `DLSSGState.numFramesToGenerateMax` will be 1). Implement MFG as
pass-through, gate on the state query, mark experimental.

**Decided policy (do not relitigate):** (a) vsync and FG mutually exclusive — enabling
FG forces vsync off, restore user setting on FG off; (b) fps cap applies to RENDERED
frames only — presented fps = cap × (1 + numFramesToGenerate).

## 1. PROCESS RULES (violating these has burned whole sessions)

1. **You cannot build or run.** Human runs Gates B (gradle build) / C (runClient+log)
   / D (screenshots). Iterate via VERIFY.md. Ask for ONE gate at a time.
2. **The folder mount is eventually-consistent and has truncated files mid-write
   TWICE.** For every code/doc change: write via shell (python/heredoc), then verify
   `md5sum file == git show HEAD:file` after committing, and check brace balance
   (parser that skips strings/comments — naive counts false-positive on braces in
   comments). NEVER trust an Edit-tool write without a shell-side verify.
3. **Git**: in-sandbox commits need `GIT_DIR=/tmp/dlssmc.git GIT_WORK_TREE=<mount>`
   (restore /tmp/dlssmc.git from `dlssmc-history.bundle` at session start: `git clone
   --bare` or cp). After EVERY commit: `git bundle create ... --all` and cp to repo
   root. User syncs with `git stash; git pull dlssmc-history.bundle main; git stash
   drop; git push origin main` (new untracked files created by us need `del` first).
4. **SL interposer routing is ALL-OR-NOTHING** (P2-2 crash post-mortem, tracker):
   every entry point in SL's SL_INTERCEPT list that Mojang calls must EITHER all go
   through the interposer or none. Partial routing = pc=0 crashes inside SL. Gate any
   new proxied chain on `SlBridge.isInstanceProxied()`.
5. Update PROJECT_TRACKER.md + VERIFY.md every iteration; commit messages
   `type(scope): summary`.

## 2. Existing architecture (files you will touch)

All under `src/client/java/com/jhp/client/`:
- `dlss/SlBridge.java` — FFM/Panama binding of `sl.interposer.dll`. Has: slInit
  (lazy, inside vkCreateInstance redirect), Preferences ABI (featuresToLoad @96,
  numFeatures @104 — **currently 1 = {kFeatureDLSS}**, ~line 385), proxy lookups,
  `slGetFeatureFunction` pattern (see `dlssSetOptions`, ~line 297 — copy this pattern
  for every new SL function), `getNewFrameToken()`. GOTCHA: `invokeExact` needs typed
  locals, never ternaries/Object (WrongMethodTypeException).
- `dlss/DlssEvaluator.java` — per-frame evaluate at LevelRenderer.render RETURN.
  Struct-fill conventions to copy: BaseStructure header = 32B (`header()` helper:
  pnext@0, GUID@8, version@24); Resource v1 112B; ResourceTag v1 64B (resource*@32,
  type@40, lifecycle@44, extent@48); Constants fill; knobs pattern (/dlssmc commands).
- `dlss/DlssMotion.java` — matrices + camera-cut reset (P2-4).
- `mixin/VulkanInstanceMixin / VulkanBackendMixin` — the @Redirect pattern for routing
  Mojang's vk* calls through interposer proc addresses. Copy this for swapchain fns.
- `mixin/GameRendererMixin` — early-restore duck iface (`DlssTargetAccess`): the
  point right after DLSS output lands in the native target, BEFORE hand/HUD = the
  HUD-less color moment.
- `DLSSmcClient.java` — /dlssmc brigadier commands.

Renderpearl facts: all images live in VK_IMAGE_LAYOUT_GENERAL forever; RenderTarget
usage=15 (no STORAGE — own raw LWJGL images needed for SL outputs); world renders
into swapped low-res target; REVERSE-Z.

## 3. SL API facts (verified against SDK v2.12.0 headers in-repo)

Feature IDs (`include/sl_core_types.h`): kFeatureDLSS=0, kFeatureReflex=3,
kFeaturePCL=4, **kFeatureDLSS_G=1000**.

`include/sl_dlss_g.h`:
- `DLSSGOptions` GUID {FAC5F1CB-2DFD-4F36-A1E6-3A9E865256C5} **v5** (line 72). First
  fields after 32B header: mode (DLSSGMode u32: eOff=0,eOn=1,eAuto=2,eDynamic=3 —
  VERIFY in header), numFramesToGenerate (u32, =1 for 2×). Derive full offsets from
  the header like P2-3 did — do NOT guess.
- `DLSSGState` GUID {CC8AC8E1-A179-44F5-97FA-E74112F9BC61} **v4** (line 149);
  `numFramesToGenerateMax` — MFG capability gate.
- `slDLSSGGetState(ViewportHandle&, DLSSGState&, const DLSSGOptions*)`,
  `slDLSSGSetOptions(ViewportHandle&, const DLSSGOptions&)` — via
  slGetFeatureFunction(kFeatureDLSS_G, ...).
- CAUTION: only set `eRequestVRAMEstimate` flag on explicit user query, never
  per-frame (guide §14/15).

`include/sl_reflex.h`:
- `ReflexOptions` GUID {F03AF81A-6D0B-4902-A651-C4965E215434} v1; ReflexMode:
  eOff=0/eLowLatency=1/eLowLatencyWithBoost=2 (verify). Set mode=eLowLatency when FG on.
- `slReflexSetOptions(const ReflexOptions&)`, `slReflexSleep(const FrameToken&)` —
  call Sleep once per frame at frame start (before input/sim).

`include/sl_pcl.h`:
- `PCLMarker`: eSimulationStart=0, eSimulationEnd=1, eRenderSubmitStart=2,
  eRenderSubmitEnd=3, ePresentStart=4, ePresentEnd=5.
- `slPCLSetMarker(PCLMarker, const FrameToken&)`.
- **The frame index in the token used for Present markers MUST match the Constants
  frame token of the frame being presented.** SL log "common constants cannot be
  found for frame N" = you desynced them. Keep ONE shared per-frame token (holder in
  SlBridge or DlssEvaluator) used by Constants, markers, and evaluate.

SL_INTERCEPT device-level list (`source/core/sl.interposer/vulkan/wrapper.cpp` ~2330):
vkGetInstanceProcAddr, vkGetDeviceProcAddr, **vkQueuePresentKHR**, vkCreateImage,
vkCmdPipelineBarrier, vkCmdBindPipeline, vkCmdBindDescriptorSets,
**vkCreateSwapchainKHR**, **vkGetSwapchainImagesKHR**, **vkDestroySwapchainKHR**,
**vkAcquireNextImageKHR**, vkBeginCommandBuffer, vkDeviceWaitIdle.
Bold = mandatory for FG. P2-2 left unrouted residuals (vkCreateImage,
vkCmdPipelineBarrier, vkBeginCommandBuffer, vkDestroyDevice/Instance) — acceptable
for SR with eDisableCLStateTracking; REVISIT for FG: route what Mojang calls.

Buffer tags for FG (guide §5): depth + mvec (already tagged for SR but with
lifecycle=2=eValidUntilEvaluate) must be **eValidUntilPresent** (verify enum value in
sl.h: ResourceLifecycle{eOnlyValidNow, eValidUntilPresent, eValidUntilEvaluate}) via
STABLE COPIES (velocity target is rewritten next frame — copy to dedicated images).
Add **kBufferTypeHUDLessColor** (verify enum value in sl.h) — copy the native target
right after the DLSS-output copy, before hand/HUD (the early-restore point).
Optional backbuffer tag: type only, NULL resource is valid (guide §5.2). UI-Alpha:
SKIPPED v1 (Minecraft has no HUD alpha) — accept HUD artifacts on generated frames.

## 4. Staged plan (each stage = one Gate B/C iteration minimum)

**FG-1 Recon (no code).** In the decompiled sources
(`.gradle/loom-cache/**/*-sources.jar`) find renderpearl's swapchain owner: grep for
vkCreateSwapchainKHR / vkAcquireNextImageKHR / vkQueuePresentKHR / recreate-on-resize
path. Output: class names, call-site descriptors, who holds the VkSwapchainKHR, how
images are wrapped, vsync/present-mode selection, and where the fps limiter lives
(Minecraft options.framerateLimit / RenderSystem.limitDisplayFPS or successor).
Record in tracker.

**FG-2 slInit features.** featuresToLoad = {kFeatureDLSS, kFeatureReflex(3),
kFeaturePCL(4), kFeatureDLSS_G(1000)}, numFeatures=4. Gate C: game must still run
with DLSS-SR working; log slIsFeatureSupported for each; expect DLSS_G "supported"
on the 4070S. If loading DLSS_G changes device-creation behavior (extra queues) the
proxies handle it — watch for new queue families in the log.

**FG-3 Swapchain routing.** @Redirect Mojang's vkCreateSwapchainKHR /
vkGetSwapchainImagesKHR / vkAcquireNextImageKHR / vkQueuePresentKHR /
vkDestroySwapchainKHR through interposer proc addresses (VulkanBackendMixin pattern);
all-or-nothing gated on isInstanceProxied(). Gate C acceptance: game runs EXACTLY as
before (FG still off), resize/F11/fullscreen-toggle don't crash, DLSS-SR still live.
This stage has the highest crash risk — do it with FG OFF so failures isolate to
routing.

**FG-4 Frame token unification + Reflex/PCL markers.** One token per frame:
eSimulationStart at client tick begin, eSimulationEnd after tick, eRenderSubmitStart/
End around GameRenderer.render, ePresentStart/End around the (now-routed)
vkQueuePresentKHR call, slReflexSleep at frame start. ReflexOptions
mode=eLowLatency. Gate C acceptance: no "common constants cannot be found for frame
N" warnings in the SL log over several minutes incl. resizes.

**FG-5 Tags + enable FG.** Stable-copy depth/MV → eValidUntilPresent tags; HUD-less
copy + tag; slDLSSGSetOptions(mode=eOn, numFramesToGenerate=1);
/dlssmc fg command: toggle + readout from slDLSSGGetState (status, fail reasons —
print them verbatim, minWidthOrHeight, numFramesToGenerateMax, estimated VRAM on
demand). Gate D: presented fps ≈ 2× real fps; HUD readable; artifacts noted.

**FG-6 Pacing policy.** FG on → force vsync off (Mojang option or present mode from
FG-1 recon; restore prior value on FG off). Verify the fps limiter throttles the
game loop only (real frames); presented fps reported via /dlssmc fg. Gate D: cap 60
→ ~120 presented.

**FG-7 MFG pass-through.** /dlssmc fgx cycles numFramesToGenerate 1→2→3 but clamps
to numFramesToGenerateMax (readout shows max; on dev HW max=1 so 2/3 refuse with a
clear message). eDynamic optional. Ship as experimental/untested.

## 5. Verification additions

Gate C grep additions: `DLSS-G|DLSSG|Reflex|PCL|dlssmc|common constants|swapchain`.
Gate D scenarios: fps overlay comparison (external: RTSS/Nvidia overlay counts
presented frames; F3 counts real), resize/F11 spam, alt-tab, portal (reset flag must
still work with FG on — camera cuts + FG interact), long session for leaks
(swapchain recreation).

Reference docs (in-repo): ProgrammingGuideDLSS_G.md (§2 checklist, §5 tags, §6
options/modes, §8 Reflex), ProgrammingGuideReflex.md, ProgrammingGuidePCL.md,
ProgrammingGuideManualHooking.md §4, and the SL ImGui debugging doc (development
DLLs only — useful for inspecting FG state live).
