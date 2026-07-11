# DLSSmc — Implementation Guide (resumable spec)

**Purpose:** everything needed to resume the two remaining heavy workstreams — motion
vectors and DLSS wiring — with the architecture already de-risked. Pairs with
[`PROJECT_TRACKER.md`](./PROJECT_TRACKER.md) (status) and
[`SPIKE_FINDINGS.md`](./SPIKE_FINDINGS.md) (risk evidence).

**Target:** Minecraft `26.3-snapshot-3` · JDK 25 · Fabric Loom 1.17 · Mixin `JAVA_25`.
**Verify loop:** [`VERIFY.md`](./VERIFY.md) (Gate A javap · B build · C runtime · D visual).

---

## 1. What works today (runtime-verified on NVIDIA 596.49 / Vulkan 1.4)

| Piece | Where | Verified |
|-------|-------|----------|
| Vulkan device + graphics queue capture | `mixin/VulkanDeviceMixin` → `dlss/DlssRenderState` | S1 — non-zero handles logged |
| Sub-pixel jitter on the **world** projection | `mixin/GameRendererMixin` `@ModifyArg` on `getBuffer(Matrix4f)` → `dlss/DlssJitter` | S2 — Halton offsets/NDC exact |
| Resolution decoupling (world→low-res→upscale, HUD native) | `mixin/GameRendererMixin` swaps `mainRenderTarget` → `dlss/DlssResolution` | S3 — visually confirmed, F8 cycles scale |
| Custom renderpearl shader pipeline | `dlss/DlssDebug` + `assets/dlssmc/shaders/core/dlss_depth_debug.fsh` | UV gradient rendered |
| MV camera-reprojection **math** | `dlss/DlssMotion` (CPU) | movement-correlated MVs, no NaNs |

Everything above is `require = 1` and green. `DlssDebug.showDepth` is off.

## 2. Key architecture facts (dug out of the deobfuscated source)

- **Renderer** is `com.mojang.renderpearl` with `backend.vulkan` (LWJGL `VkDevice`/`VkQueue`).
  Raw native handle for Streamline = `VkDevice.address()` / `VkQueue.address()`.
- **Single render target.** World *and* GUI render into one window-sized `mainRenderTarget`
  (`MainTarget`, `RGBA8_UNORM` + `D32_FLOAT`). `LevelRenderer` resolves its target via
  `gameRenderer.mainRenderTarget()` (and sizes the render graph from its width/height), so
  swapping that field for `renderLevel` redirects the **entire** world render graph — this
  is how resolution decoupling works.
- **World projection** is uploaded via `ProjectionMatrixBuffer.getBuffer(Matrix4f)` (no
  `Projection.getMatrix()`); the HUD/item projection uses `getBuffer(Projection)`. Jitter
  targets the former only.
- **Depth is NOT sampleable as a texture.** Mojang only ever binds `depthTextureView` as a
  *depth attachment* (`withDepthAttachment`); the only sampled "depth" is a **color**
  depth-bounds target a prior pass wrote. A plain `sampler2D` on the depth texture returns 0
  (confirmed: black). This is the crux of the motion-vector approach choice below.
- **Custom full-screen pass recipe** (proven working):
  `RenderPipeline.builder().withVertexShader("core/screenquad")` (reusable fullscreen VS)
  `.withFragmentShader(Identifier "dlssmc:core/<name>")` `.withBindGroupLayout(...)`
  `.withColorTargetState(ColorTargetState.DEFAULT)` `.withPrimitiveTopology(TRIANGLES)`
  `.withCull(false).build()`; then `RenderSystem.getCompiledPipeline(pipeline)`,
  `createCommandEncoder().createRenderPass(label, colorView, Optional.empty(), depthViewOrNull, OptionalDouble.empty())`,
  `pass.setPipeline(...)`, `bindDefaultUniforms`, `bindTexture(name, view, sampler)`,
  `setUniform(name, GpuBufferSlice)`, `draw(3,1,0,0)`.
  UBO write pattern: `device.createBuffer(...)` + `Std140Builder.onStack(...).putMat4f(...)` +
  `createCommandEncoder().writeToBuffer(slice, byteBuffer)` (see `ProjectionMatrixBuffer`).

## 3. Motion vectors (P1-7) — ✅ DONE (2026-07-10, runtime-verified)

**Final architecture** (supersedes the plan below, kept for context): two layers into
one RG16F target at internal res, both at `LevelRenderer.render` RETURN (CRITICAL — the
level depth is CLEARED for renderItemInHand before renderLevel returns):
(1) fullscreen camera fallback (`DlssVelocity`, single folded reprojection matrix via
ProjectionMatrixBuffer + PROJECTION layout, assumed depth 0.0002 reverse-Z);
(2) terrain prepass (`DlssTerrainVelocity` + `LevelRendererMixin`): replays
`ChunkSectionsToRender` OPAQUE draws with a TERRAIN_SNIPPET-derived pipeline
(`terrain_velocity.vsh/.fsh`, DlssReprojection UBO, uniforms SNAPSHOTTED at
executeSolid), level depth GEQUAL/no-write/+2-bias. `/dlssmc mv` overlays it
(alpha-blend over backbuffer, log-scaled). Entities = P1-8 (deferred post-DLSS).

### Original analysis (historical)

The math (`DlssMotion`) is done: `clip = proj·viewRot·(worldPos − cameraPos)`; store the
unjittered current/previous view-proj + camera position; MV = prevNDC − currNDC. What's
missing is per-pixel depth in a shader.

**Approach A — depth-resolve to color, then a screen-space reprojection pass.**
Get scene depth into a sampleable **color** target, then run one full-screen pass that
reconstructs world pos and reprojects.
- Getting depth→color is the catch (raw depth isn't sampleable). Options: a pass that binds
  the level depth as a *depth attachment* with depth-test `ALWAYS` writing `gl_FragCoord.z`
  is **not** sufficient (fullscreen quad depth ≠ scene depth). The realistic route is to
  find/confirm a renderpearl depth-copy to a `R32F` color (investigate `copyTextureToTexture`
  across aspects, or a dedicated depth-bounds-style pass like Mojang's OIT `DEPTH_BOUNDS`).
- Pros: one added pass, no geometry-pipeline changes. Cons: depends on solving depth→color.

**Approach B — MRT velocity during the geometry pass (DLSS-canonical, recommended).**
Add a second color attachment (`RG16F` velocity) to the world render pass; the chunk/entity
vertex shaders compute `prevClip` (from previous-frame model + view-proj) and `currClip`,
and the fragment writes screen-space velocity. No depth sampling needed.
- Pros: correct MVs including dynamic objects; the way real engines do it. Cons: invasive —
  touches the terrain/entity pipelines and their shaders; needs previous-frame transforms per
  object (static terrain only needs camera reprojection, so start there).
- Start: terrain only (camera-only velocity), verify by rendering the velocity target to
  screen as color (reuse `DlssDebug` pattern), then add entities.

Either way, next concrete steps: (1) create the `RG16F` velocity `TextureTarget`; (2) build
the velocity pass/attachment; (3) debug-visualize velocity as color; (4) tune sign/space
against `DlssMotion`'s CPU values; (5) feed to DLSS in Phase 2.

**DECIDED (2026-07-10): Approach B, staged.**

- **Slice 1 — camera-only fullscreen velocity (CODED, awaiting Gate A2/B).** Key insight:
  for pure camera *rotation*, reprojection is depth-independent (scaling the camera-relative
  ray scales clip by the same factor; NDC is invariant), so a fullscreen pass at an assumed
  depth (0.5, matching `DlssMotion.debugSanityCheck`) is exact for mouse-look and only
  approximate for translation. Because perspective division is invariant under homogeneous
  scaling, the whole chain folds into ONE matrix on the CPU —
  `DlssMotion.reprojectionMatrix()` = prevVP·T(camDelta)·inv(curVP) — so the pass reuses
  Mojang's `ProjectionMatrixBuffer` + the stock `BindGroupLayouts.PROJECTION` layout
  (shader block mirrors vanilla `Projection/ProjMat`; **no custom UBO**). Stands up the
  RG16F target (`GpuFormat.RG16_FLOAT` — javap-confirmed), the pass, the debug overlay
  (`DlssVelocity.showDebug`), and the sign/space conventions — zero geometry-pipeline
  recon needed. Files: `dlss/DlssVelocity.java`, `shaders/core/dlss_velocity.fsh`,
  `shaders/core/dlss_velocity_debug.fsh`; hooks at `renderLevel` RETURN in
  `GameRendererMixin`. Gate-C crash confirmed `ColorTargetState.DEFAULT` = RGBA8; fixed
  with `new ColorTargetState(Optional.empty(), RG16_FLOAT, WRITE_ALL)` (record ctor
  javap-confirmed, `docs/javap_cts.txt`). Bonus recon from that dump: `RenderPipeline.Builder`
  has `withColorTargetState(int index, …)` + `withUnusedColorTargetState(int)` — renderpearl
  natively supports MRT pipelines, de-risking slice 2.
  Gate D expectation with the overlay on: standing still ⇒ flat 0.5-gray; mouse-look ⇒
  smooth red/green full-screen gradient flipping sign with look direction; center-pixel GPU
  value should equal the logged CPU `mvNDC` × 0.5 (UV space).
- **Slice 2 — MRT terrain velocity (recon-gated).** Replace the assumed depth with real
  geometry: 2nd RG16F color attachment on the world geometry pass; chunk VS computes
  curr/prev clip (camera-only for static terrain). Needs Gate A2 part 2: `LevelRenderer` /
  `RenderPipelines` dumps + shipped terrain shader sources, and whether renderpearl's
  `createRenderPass` accepts multiple color attachments.
- **Slice 3 — entities (P1-8):** previous-frame model transforms; after slice 2 works.

## 4. DLSS wiring (Phase 2)

1. **Download** the NVIDIA Streamline SDK (registration) and **read the EULA** (Risk 3 /
   P2-6) — shipping gate.
2. **Inject VK device/instance extensions + features** DLSS/NGX need *before*
   `vkCreateDevice`. Targets: `backend.vulkan.VulkanBackend` (`createDevice`/`vkCreateDevice`),
   `VulkanInstance`, `init.{FeatureSet, VulkanFeature, VulkanPNextStruct}`. In manual-hook mode
   SL does **not** add these for us.
3. **Manual hook** Streamline against Minecraft's device: `slSetVulkanInfo` (mandatory when
   the app owns `vkCreateInstance`/`vkCreateDevice`), `slGetNativeInterface`/`slUpgradeInterface`.
   Pass the captured `VkDevice`/`VkQueue`/physical device via `DlssRenderState`. Native glue
   via Java FFM (Project Panama) or a small JNI shim.
4. **Tag per-frame buffers** into SL `sl::Constants` / DLSS tags: low-res color = the
   `DlssResolution` level target; output = native `mainRenderTarget`; depth; motion vectors
   (§3); jitter offset = `DlssJitter.pixelOffsetX/Y()`; exposure/HDR flags. Replace the
   NEAREST upscale blit at `renderLevel` RETURN with the DLSS evaluate call.
5. **Reset flag** on camera cuts — `DlssJitter.requestReset()` is already wired; call it from
   teleport/portal/respawn/dimension-change events, consume via `DlssJitter.consumeReset()`.

## 5. File map

```
src/client/java/com/jhp/client/
  DLSSmcClient.java            client init; F8 render-scale keybind
  dlss/DlssRenderState.java    captured VkDevice / VkQueue / family (for Streamline)
  dlss/DlssJitter.java         Halton(2,3) jitter; pixel offset + reset flag (DLSS inputs)
  dlss/DlssResolution.java     low-res level TextureTarget + mainRenderTarget swap; scale cycle
  dlss/DlssMotion.java         unjittered view-proj + camera history; CPU reprojection check;
                               exports inverse + delta-folded matrices for the GPU UBO
  dlss/DlssVelocity.java       P1-7: RG16F velocity target + fullscreen camera fallback
                               (single reprojection matrix) + /dlssmc mv overlay
  dlss/DlssTerrainVelocity.java P1-7: terrain velocity prepass (replays chunk draws,
                               snapshotted uniforms, depth-tested GEQUAL/no-write)
  mixin/LevelRendererMixin.java captures ChunkSectionsToRender + uniforms at executeSolid;
                               runs both velocity passes at render RETURN (pre depth-clear)
  dlss/DlssDebug.java          custom-pipeline helper (proven); depth/plumbing debug pass
  mixin/VulkanDeviceMixin.java capture handles at VulkanDevice.<init> TAIL (require=1)
  mixin/GameRendererMixin.java jitter @ModifyArg + resolution swap + MV capture on renderLevel
  mixin/ProjectionMixin.java   RETIRED (HUD-only; not in mixins.json)
src/client/resources/assets/dlssmc/shaders/core/dlss_depth_debug.fsh
src/client/resources/assets/dlssmc/shaders/core/dlss_velocity.fsh
src/client/resources/assets/dlssmc/shaders/core/dlss_velocity_debug.fsh
```

## 6. Gotchas for future sessions

- **Git DB lives in the sandbox `/tmp`**, exported to `dlssmc-history.bundle` (the Cowork
  mount blocks file deletion, breaking an in-place `.git`). Restore via
  `git clone dlssmc-history.bundle`. A non-functional `.git/` stub may sit in the folder —
  delete it on a real machine.
- **Pin the snapshot.** Re-run Gate A (`javap`) after any version bump; renderpearl internals
  shift. All mixin descriptors are javap-confirmed for `26.3-snapshot-3` only.
- **Dev-world saves corrupt** if `runClient` is force-killed (`EOFException` on load) — make a
  fresh world rather than reopening.
