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
- **Next up:** on the JDK-25 dev machine, run `./gradlew genSources` then `./gradlew
  build`; confirm the `@Inject`/`@Accessor`/`getMatrix`/`renderLevel` descriptors
  in-IDE; verify non-null `VkDevice` at runtime (**S1**) and numeric jitter offset
  (**S2**).
- **Blocked/awaiting human:** Task **P2-6** (license read) and running an actual
  Vulkan-capable dev instance (needs the NVIDIA RTX machine + `genSources` in IntelliJ;
  cannot be done in this sandbox).

---

## Milestones

- [x] **M0 — Environment & recon** — template scaffolded, snapshot jar resolved, risks spiked.
- [ ] **M1 — Handles + jitter** — Vulkan handles captured at runtime; jitter provably applied.
- [ ] **M2 — Resolution decoupling** — internal 3D res < output; UI composited at native res.
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
- [x] P1-1 Vulkan handle-capture Mixin scaffold (`VulkanDeviceMixin` + `DlssRenderState` holder). *(compiles; runtime unverified)*
- [ ] P1-2 Confirm `@Inject`/`@Accessor` descriptors vs `genSources`; verify non-null `VkDevice`/`VkQueue` at runtime (**S1**).
- [ ] P1-3 Capture swapchain images + per-frame command buffer(s) needed for SL tagging.
- [~] P1-4 Jitter injection **scaffolded**: `DlssJitter` (Halton(2,3), 16-phase, pixel+NDC offset, reset flag) + `GameRendererMixin` scopes jitter to `renderLevel` + `ProjectionMixin` jitters `Projection.getMatrix()` return. Confirm descriptors + verify numerically (**S2**); feed real render res after P1-5.
- [ ] P1-5 Decouple internal 3D render resolution from output; render world to an offscreen target (**S3**).
- [ ] P1-6 Composite HUD/UI at native res after upscale (hook `PostChain`/`PostPass` or main render path).
- [ ] P1-7 Motion vectors: camera/global MVs first (reprojection of static geometry) (**S4** partial).
- [ ] P1-8 Motion vectors: per-object for entities (previous-frame model transforms).
- [ ] P1-9 Motion vectors: chunks/terrain (mostly camera-only) + particles/water passes as needed.
- [ ] P1-10 Depth handling: confirm depth format/space; linearize if DLSS needs it.

### Phase 2 — DLSS integration
- [ ] P2-1 Inject DLSS-required VK **instance/device extensions + features** into `VulkanBackend`/`VulkanInstance`/`FeatureSet` before `vkCreateDevice`.
- [ ] P2-2 Wire Streamline **manual hooking** (`slSetVulkanInfo`, `slGetNativeInterface`/`slUpgradeInterface`) against Minecraft's device.
- [ ] P2-3 Tag per-frame buffers into SL: color (low-res), output, depth, motion vectors, jitter offset, exposure/HDR.
- [ ] P2-4 Fire DLSS **reset flag** on teleport/portal/respawn/dimension change (**S7**).
- [ ] P2-5 Artifact tuning: ghosting on foliage/particles/thin geometry; disocclusion at chunk edges (**S6**).
- [ ] P2-6 **[!] Human:** read NVIDIA Streamline SDK EULA in the release zip; resolve redistribution before shipping (**Risk 3**).

### Verification (ongoing)
- [~] V-1 Build compiles against 26.3-snapshot-3 — **static checks only** in sandbox (no JDK 25 / no network); run `./gradlew build` on the dev machine to confirm.
- [ ] V-2 Runtime handle-capture log check (needs Vulkan dev instance).
- [ ] V-3 Numeric jitter check.
- [ ] V-4 Motion-vector debug visualization.
- [ ] V-5 DLSS active + internal-res reduction confirmed.

---

## Injection-target reference (from spike)

| Purpose | Target class | Member / signature note |
|---------|--------------|-------------------------|
| Capture `VkDevice` + queues | `com.mojang.renderpearl.backend.vulkan.VulkanDevice` | field `vkDevice: org.lwjgl.vulkan.VkDevice`; `graphicsQueue/computeQueue/transferQueue: VulkanQueue`; ctor tail `(VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long, CheckpointExtension)` |
| Raw `VkQueue` | `...backend.vulkan.VulkanQueue` | `fetchVkQueue(): org.lwjgl.vulkan.VkQueue`, `queueFamilyIndex` |
| Device/extension creation | `...backend.vulkan.VulkanBackend` | `createDevice`, `vkCreateDevice`, `setWindowHints` |
| VK features/extensions | `...backend.vulkan.init.{FeatureSet, VulkanFeature, VulkanPNextStruct}` | add DLSS-required features/extensions |
| Jitter (apply) | `net.minecraft.client.renderer.Projection` | `getMatrix(): Matrix4f` — jitter the returned matrix (only while level-render window open) |
| Jitter (scope) | `net.minecraft.client.renderer.GameRenderer` | `renderLevel` HEAD/RETURN opens/closes the jitter window; separate `levelProjectionMatrixBuffer` vs `hud3dProjectionMatrixBuffer` |
| Composite / post | `net.minecraft.client.renderer.{PostChain, PostPass, PostChainConfig}` + `...renderer.oit.*` | upscale composite + UI-at-native pass |

Raw native handle for SL = LWJGL `VkDevice.address()` / `VkQueue.address()`.

> ⚠️ Method **descriptors** above are name-confirmed but not signature-confirmed —
> validate against `genSources` before finalizing each `@Inject`.

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
| 2026-07-10 | Jitter scoped to the level render via a `renderLevel` active-window flag; jitter applied at `Projection.getMatrix()` return as a copy. | No `getProjectionMatrix` exists in 26.3; `Projection.getMatrix()` is the real seam, and windowing keeps the HUD unjittered without shadowing uncertain fields. |

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
