# DLSSmc — Project Tracker

> **Single source of truth for status.** Update this file as work progresses so any
> future session can resume without re-deriving context. See [`PRD.md`](./PRD.md) for
> goals/scope and [`SPIKE_FINDINGS.md`](./SPIKE_FINDINGS.md) for the evidence behind
> decisions.

**Pinned target:** `26.3-snapshot-3` · **Toolchain:** JDK 25, Loader 0.19.3, Loom
1.17-SNAPSHOT, Fabric API 0.154.3+26.3 · **Last updated:** 2026-07-10

---

## ▶ RESUME HERE (where we left off)

> **To verify/help this iteration, follow [`VERIFY.md`](./VERIFY.md).** Start with Gate A (the `javap` dumps).

- **Done:** Risk spikes 1/1b/2/3 complete (see findings). PRD + tracker written. Git
  baseline established. Phase-1 Vulkan handle-capture scaffold (`VulkanDeviceMixin` +
  `DlssRenderState`) **and** jitter scaffold (`DlssJitter` + `GameRendererMixin` +
  `ProjectionMixin`) added; all statically verified.
- **Descriptors LOCKED** via `javap` (Gate A, `docs/javap_dump.txt`): sole `VulkanDevice`
  ctor + `vkDevice`/`graphicsQueue` fields; `VulkanQueue` is a record (`vkQueue()`,
  `queueFamilyIndex()`); `GameRenderer.renderLevel(DeltaTracker)` void; `Projection.
  getMatrix(Matrix4f)` + `width()/height()`. Mixins updated to match; queue capture added.
- **S1 CONFIRMED** (Gate C): device+queue captured on NVIDIA 596.49 / Vulkan 1.4;
  `VulkanDeviceMixin` promoted to `require=1`.
- **S2 CONFIRMED** (Gate C): both jitter mixins fire; offsets/NDC exact; all 3 mixins
  now `require=1`. **Milestone M1 complete.**
- **M2 DONE (S3 confirmed):** half-res world upscales to native, HUD crisp. P1-6 also
  satisfied (HUD native by construction). We now have all the *rendering* pieces DLSS
  needs except motion vectors.
- **CONSOLIDATED (2026-07-10) — BUILD SUCCESSFUL, all working features verified.** Decision:
  stop at a proven foundation before the multi-week MV/DLSS-native grind. Wrote
  `IMPLEMENTATION_GUIDE.md` (resumable spec for MV approaches A/B + DLSS wiring) + real
  README. Everything that works is `require=1`, runtime-verified, committed.
  Note: F8 render-scale keybind was **deferred** — the fabric key-mapping API package
  changed in this version; render scale is set via `DlssResolution.scale` (default 0.5),
  and `cycleScale()` + a code TODO are in place for wiring the keybind later.
  To resume: read `docs/IMPLEMENTATION_GUIDE.md` → §3 (motion vectors) or §4 (DLSS).
- **2026-07-10 (later session) — P1-7 DECIDED: Approach B (MRT velocity), terrain-first.**
  Slice 1 (camera-only velocity) coded: `dlss/DlssVelocity` (RG16F target + fullscreen
  pass + Gate-D debug overlay, `showDebug=true`), `dlss_velocity.fsh` /
  `dlss_velocity_debug.fsh`, wired at `renderLevel` RETURN. After Gate-A2 javap dumps
  (`docs/javap_mv_dump.txt`, `docs/class_grep.txt`): reworked to fold the whole
  reprojection into ONE matrix (`DlssMotion.reprojectionMatrix()` =
  prevVP·T(camDelta)·inv(curVP); exact by homogeneous-scale invariance) and reuse
  `ProjectionMatrixBuffer` + stock `BindGroupLayouts.PROJECTION` — no custom UBO.
  `GpuFormat.RG16_FLOAT` javap-confirmed.
- **S4 CONFIRMED (Gate C+D, 2026-07-10 19:30):** velocity pass compiled + running at
  internal res (1280x720 @ native 2560x1440); debug overlay shows the correct camera-
  reprojection field (smooth saturated RG gradients, sign flip across motion boundary,
  HUD native on top); MV sanity logs alongside; zero renderer errors. One Gate-C crash
  en route: `ColorTargetState.DEFAULT` = RGBA8 → fixed with the record ctor
  `(Optional.empty(), RG16_FLOAT, WRITE_ALL)`. Bonus recon (`docs/javap_cts.txt`):
  `RenderPipeline.Builder.withColorTargetState(int index, …)` + `withUnusedColorTargetState`
  ⇒ renderpearl natively supports MRT — slice 2 de-risked.
  **Debug toggle added:** `/dlssmc mv` (translucent scene+velocity composite, opacity
  scales with motion; default OFF) and `/dlssmc scale` (render-scale cycle — supersedes
  the deferred F8 keybind TODO) via fabric-command-api-v2 client commands.
  **Gate A2 part 2 DONE** (`docs/javap_terrain_dump.txt`, `class_grep2.txt`,
  `shader_list.txt`): terrain pipelines = `RenderPipelines.SOLID_TERRAIN` /
  `CUTOUT_TERRAIN` / `TRANSLUCENT_TERRAIN` (+ `TERRAIN_SNIPPET`, `BLOCK_SNIPPET`);
  geometry pass lives in `LevelRenderer.addMainPass` / `executeSolid` /
  `executeClassicTransparency`; shipped shaders to extend: `terrain.vsh/.fsh`,
  `block.vsh/.fsh`.
  **Next: slice 2 (MRT terrain velocity):** extract those 4 shader sources, then
  (1) index-1 RG16F `ColorTargetState` on the terrain pipelines, (2) velocity output in
  their FS (camera-only reprojection from the VS world pos), (3) 2nd color attachment on
  the main pass's render-pass creation, (4) verify via the `/dlssmc mv` overlay, then
  drop `ASSUMED_DEPTH`.
- **Blocked/awaiting human:** Gate A2 + Gate B on the dev machine; Task **P2-6** (license
  read); Vulkan-capable dev instance for Gates C/D.

---

## Milestones

- [x] **M0 — Environment & recon** — template scaffolded, snapshot jar resolved, risks spiked.
- [x] **M1 — Handles + jitter** — device+queue captured (S1) and jitter applied with correct Halton offsets (S2), both runtime-verified 2026-07-10.
- [x] **M2 — Resolution decoupling** — VISUALLY CONFIRMED (S3): half-res world upscaled to native, HUD crisp (2026-07-10).
- [ ] **M3 — Motion vectors** — correct per-pixel MVs for chunks + entities (debug-viz verified).
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
- [~] P1-7 Motion vectors — reprojection math CPU-verified; custom-shader **pipeline plumbing confirmed** (UV gradient). **BLOCKER found:** renderpearl does not expose the depth buffer for direct `sampler2D` sampling — Mojang only uses `depthTextureView` as a *depth attachment*; the sole sampled "depth" is a *color* depth-bounds target. A screen-space camera-reprojection MV pass needs per-pixel depth, so it requires either:
  - (A) a **depth-resolve pass** that writes scene depth into a color/R32F target (then sample that), or
  - (B) **MRT velocity output** written during the geometry pass (chunk/entity pipelines emit velocity to a 2nd color attachment) — the DLSS-canonical way, but invasive across many pipelines.
  This is the biggest, most iteration-heavy Phase-1 item (as the brief predicted).
  **DECIDED 2026-07-10: Approach B**, staged — slice 1 (camera-only fullscreen velocity, exact for rotation, coded, awaiting Gate A2/B) → slice 2 (MRT terrain) → P1-8 (entities).
- [ ] P1-8 Motion vectors: per-object for entities (previous-frame model transforms).
- [ ] P1-9 Motion vectors: chunks/terrain (mostly camera-only) + particles/water passes as needed.
- [~] P1-10 Depth: level target uses `D32_FLOAT`. Raw depth texture is **not sampleable** as `sampler2D` in renderpearl (returns 0). Reverse-Z/space still TBD once depth is obtained via (A) or (B) above.

### Phase 2 — DLSS integration
- [ ] P2-1 Inject DLSS-required VK **instance/device extensions + features** into `VulkanBackend`/`VulkanInstance`/`FeatureSet` before `vkCreateDevice`.
- [ ] P2-2 Wire Streamline **manual hooking** (`slSetVulkanInfo`, `slGetNativeInterface`/`slUpgradeInterface`) against Minecraft's device.
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
| 2026-07-10 | Vulkan backend only; OpenGL out of scope. | DLSS never supported GL; renderer has a clean Vulkan backend. |
| 2026-07-10 | Streamline **manual hooking**, not interposer. | Minecraft owns `vkCreateDevice`; SL supports app-owned devices via `slSetVulkanInfo`. |
| 2026-07-10 | Build our own motion vectors. | Spike R1: no MV/TAA infra exists in 26.3-snapshot-3. |
| 2026-07-10 | DLSS **SR only** for v1 (no FG/RR/Reflex). | Scope control for prototype. |
| 2026-07-10 | Git DB in sandbox `/tmp`, work-tree on the mount; export `.bundle` for persistence. | Cowork mount blocks file deletion, which breaks an in-place `.git`. |
| 2026-07-10 | Jitter the **world** projection via `@ModifyArg` on `ProjectionMatrixBuffer.getBuffer(Matrix4f)` in `renderLevel`; retire the `Projection.getMatrix()` hook. | Source read showed the world uses `getBuffer(Matrix4f)` (no `getMatrix`); the old hook only jittered the HUD/item projection. `@ModifyArg` targets the exact world upload. |
| 2026-07-10 | **P1-7 = Approach B (MRT velocity)**, staged: camera-only fullscreen pass first, then MRT terrain, then entities. | Depth is not sampleable (A is blocked on an unsolved depth-resolve); B is DLSS-canonical and extends to entities. The camera-only slice is depth-independent for rotation, so it stands up the target/UBO/pass/conventions with zero recon on geometry pipelines. |

---

## Environment / setup state

- Snapshot jar resolved by Loom: `.gradle/loom-cache/.../minecraft-clientOnly-66c3572c59-26.3-snapshot-3.jar`.
- `build.gradle` / `gradle.properties` already match the brief (no Yarn line; Loom id
  `net.fabricmc.fabric-loom`; Java 25; Mixin `JAVA_25`).
- **Still required on the real dev machine (cannot be done in this sandbox):**
  - JDK 25 (Temurin) + IntelliJ 2025.3+.
  - `./gradlew genSources` to browse decompiled source & confirm descriptors.
  - NVIDIA RTX GPU + driver with Vulkan 1.2 (dynamic rendering + push descriptors).
  - `./gradlew runClient`, then enable Video Settings → Graphics → "Prefer Vulkan (Experimental)".
  - Download NVIDIA Streamline SDK release zip (registration) and read its EULA.

---

## Git & cross-session persistence

The Cowork folder mount blocks file **deletion**, which breaks an in-place `.git`
(lock files can't be cleaned up). Workaround in use:

- Git database lives at `/tmp/dlssmc.git` in the sandbox; work-tree is this folder.
- History is exported to **`dlssmc-history.bundle`** in the repo root so it survives
  across sessions and can be restored on the real machine.

**To restore history on your machine (Windows, where delete works):**
```
# in an empty dir, or after removing the stub .git that the sandbox left behind:
git clone dlssmc-history.bundle dlssmc
# or, into an existing checkout:
git bundle verify dlssmc-history.bundle
git fetch dlssmc-history.bundle main:restored-main
```
> Note: the sandbox left a non-functional `.git/` stub in the folder that it could not
> delete. On your machine, delete that folder and use the bundle (or just
> `git init` fresh) — it does not affect the source files.

**Next session with a live sandbox:** re-point git with
`export GIT_DIR=/tmp/dlssmc.git GIT_WORK_TREE=<repo>` after restoring from the bundle,
or re-init and continue.
