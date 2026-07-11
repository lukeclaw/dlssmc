# How to help / verify — the iteration loop

Claude works in a sandbox with **no JDK 25, no GPU, no network**, so it cannot compile,
run, or see the game. You are the hands. Each iteration has up to four gates. Run the
ones Claude asks for, then paste the marked output back into chat.

> **Shell:** commands are given for **PowerShell** (the Windows default). Run everything
> from the repo root: `D:\Projects\dlssmc`. Git Bash works too — swap `.\gradlew.bat`
> for `./gradlew` and use the bash `JAR=$(find ...)` form.
>
> **Tip:** any command below that produces output for Claude can be redirected to a file
> in `docs\` (e.g. `... *> docs\javap_dump.txt`). Because this folder is mounted, Claude
> can read that file directly — you just say "done" instead of pasting.

---

## Gate A — Confirm descriptors (do this FIRST, once per snapshot)

This replaces Claude's constant-pool guesses with the real signatures. Needs JDK 25's
`javap` (you have it now).

```powershell
$JAR = (Get-ChildItem -Recurse .gradle\loom-cache -Filter 'minecraft-clientOnly-*-26.3-snapshot-3.jar' | Select-Object -First 1).FullName
$JAR   # should print a path, not blank

& {
  javap -p -cp "$JAR" com.mojang.renderpearl.backend.vulkan.VulkanDevice
  javap -p -cp "$JAR" com.mojang.renderpearl.backend.vulkan.VulkanQueue
  javap -p -cp "$JAR" net.minecraft.client.renderer.GameRenderer
  javap -p -cp "$JAR" net.minecraft.client.renderer.Projection
} *> docs\javap_dump.txt
```

Then say **"done"** — Claude reads `docs\javap_dump.txt` from the folder. (Git Bash: use
`JAR=$(find .gradle/loom-cache -name 'minecraft-clientOnly-*-26.3-snapshot-3.jar' | head -1)`
and run the four `javap` lines without the `& { }` wrapper.)

**Paste back:** the full output of all four. From it Claude will pin:
- `VulkanDevice`: exact `<init>` signature + `vkDevice` field + queue getters (for `VulkanDeviceMixin`).
- `GameRenderer`: exact `renderLevel(...)` descriptor (for `GameRendererMixin`).
- `Projection`: exact `getMatrix()` name/return (for `ProjectionMixin`).

---

## Gate A2 — MV recon (P1-7, run once for the velocity work)

Two parts: **(1)** the API surface the new `DlssVelocity` pass guesses at, **(2)** the
terrain-pipeline internals needed for slice 2 (MRT velocity in the geometry pass).

```powershell
$JAR = (Get-ChildItem -Recurse .gradle\loom-cache -Filter 'minecraft-clientOnly-*-26.3-snapshot-3.jar' | Select-Object -First 1).FullName
$JAR   # should print a path, not blank

# (1) API surface used by DlssVelocity (UBO + formats + pipeline)
& {
  javap -p -cp "$JAR" com.mojang.renderpearl.api.GpuFormat
  javap -p -cp "$JAR" net.minecraft.client.renderer.BindGroupLayouts
  javap -p -cp "$JAR" com.mojang.renderpearl.api.pipeline.ColorTargetState
  javap -p -cp "$JAR" com.mojang.renderpearl.api.pipeline.RenderPipeline
  javap -p -cp "$JAR" 'com.mojang.renderpearl.api.pipeline.RenderPipeline$Builder'
  javap -p -cp "$JAR" com.mojang.renderpearl.api.buffers.Std140Builder
  javap -p -cp "$JAR" com.mojang.renderpearl.api.buffers.GpuBuffer
  javap -p -cp "$JAR" com.mojang.renderpearl.api.buffers.GpuBufferUsage
  javap -p -cp "$JAR" com.mojang.renderpearl.api.buffers.GpuBufferSlice
  javap -p -cp "$JAR" com.mojang.renderpearl.api.device.GpuDevice
  javap -p -cp "$JAR" com.mojang.renderpearl.api.commands.CommandEncoder
  javap -p -cp "$JAR" com.mojang.renderpearl.api.commands.RenderPass
  javap -p -cp "$JAR" net.minecraft.client.renderer.ProjectionMatrixBuffer
  javap -p -cp "$JAR" com.mojang.blaze3d.pipeline.TextureTarget
} *> docs\javap_mv_dump.txt

# (2) Terrain/chunk pipeline recon for MRT (slice 2)
& {
  javap -p -cp "$JAR" net.minecraft.client.renderer.LevelRenderer
  javap -p -cp "$JAR" net.minecraft.client.renderer.RenderPipelines
} *> docs\javap_terrain_dump.txt

# Class-name grep in case RenderPipelines/section classes are named differently
jar --list --file "$JAR" | Select-String -Pattern 'Pipelines|Section|Terrain|Chunk' *> docs\class_grep.txt

# Shipped terrain shader sources (adjust names from the listing if needed)
jar --list --file "$JAR" | Select-String -Pattern 'shaders/core' *> docs\shader_list.txt
```

Then say **"done"** — Claude reads the four `docs\*.txt` files. If a `javap` line errors
with "class not found", leave it; the error text in the dump is itself the answer.

After reading `docs\shader_list.txt`, Claude will name the 2–3 terrain shader files to
extract (e.g. `jar --extract --file "$JAR" assets/minecraft/shaders/core/terrain.vsh`
run from `docs\`).

---

## Gate A3 — MRT injection recon (P1-7 slice 2, run once)

Pins the remaining APIs for the terrain-velocity MRT work: custom bind-group layouts
(for the DlssReprojection UBO in the terrain shaders), multi-attachment render passes,
snippet-based pipeline building, and the exact call sites inside `LevelRenderer`.

```powershell
$JAR = (Get-ChildItem -Recurse .gradle\loom-cache -Filter 'minecraft-clientOnly-*-26.3-snapshot-3.jar' | Select-Object -First 1).FullName

& {
  javap -p -cp "$JAR" com.mojang.renderpearl.api.pipeline.BindGroupLayout
  javap -p -cp "$JAR" 'com.mojang.renderpearl.api.pipeline.BindGroupLayout$Builder'
  javap -p -cp "$JAR" 'com.mojang.renderpearl.api.pipeline.BindGroupLayout$UniformDescription'
  javap -p -cp "$JAR" com.mojang.renderpearl.api.commands.CommandEncoder
  javap -p -cp "$JAR" com.mojang.renderpearl.api.commands.RenderPass
  javap -p -cp "$JAR" com.mojang.renderpearl.api.pipeline.RenderPipeline
  javap -p -cp "$JAR" 'com.mojang.renderpearl.api.pipeline.RenderPipeline$Snippet'
  javap -p -cp "$JAR" net.minecraft.client.renderer.LevelTargetBundle
} *> docs\javap_a3_dump.txt

# Bytecode of LevelRenderer (large file, ~1-2 MB is fine) — Claude greps it for the
# createRenderPass / setPipeline call sites in addMainPass/executeSolid.
javap -c -p -cp "$JAR" net.minecraft.client.renderer.LevelRenderer *> docs\javap_levelrenderer_c.txt
```

Then say **"done"**.

---

## Gate A4 — Velocity-prepass recon (P1-7 slice 2, final round)

```powershell
$JAR = (Get-ChildItem -Recurse .gradle\loom-cache -Filter 'minecraft-clientOnly-*-26.3-snapshot-3.jar' | Select-Object -First 1).FullName

& {
  javap -p -cp "$JAR" net.minecraft.client.renderer.chunk.ChunkSectionsToRender
  javap -p -cp "$JAR" net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup
  javap -p -cp "$JAR" com.mojang.renderpearl.api.pipeline.DepthStencilState
  javap -p -cp "$JAR" com.mojang.renderpearl.api.pipeline.UniformType
  javap -p -cp "$JAR" 'com.mojang.renderpearl.api.commands.RenderPassDescriptor$Builder'
} *> docs\javap_a4_dump.txt

javap -c -p -cp "$JAR" net.minecraft.client.renderer.chunk.ChunkSectionsToRender *> docs\javap_cstr_c.txt
javap -c -p -cp "$JAR" net.minecraft.client.renderer.RenderPipelines *> docs\javap_pipelines_c.txt
```

Then say **"done"**.

---

## Gate B — Compile

```bash
./gradlew --offline genSources   # first time only; makes decompiled source browsable in-IDE
./gradlew build
```

**Paste back:**
- If it fails: the block starting at the first `error:` (and the `> Task :... FAILED` line).
- If it passes: just `BUILD SUCCESSFUL`.

Common expected fixes Claude may make from your output: a wrong field/method name, a
package that moved, or a `require = 0` mixin that silently found no target (see Gate C).

---

## Gate C — Runtime (handle capture = S1, jitter active = S2)

```bash
./gradlew runClient
```
Then in-game: **Options → Video Settings → Graphics → "Prefer Vulkan (Experimental)"**,
load any world, move the camera.

**Paste back** the lines matching our tag and any mixin problems. Easiest: after closing
the game,

```bash
grep -E "DLSSmc|Mixin apply|was not applied|redirector|InvalidInjectionException" run/logs/latest.log
```

What Claude is looking for:
- `[DLSSmc] Vulkan device captured: device=0x...` → **S1 pass** (non-zero handle).
- No `InvalidInjectionException` / "mixin ... was not applied" for our three mixins.
- If a mixin didn't apply, that's fine — it means the descriptor guess missed; the log
  tells us the real target and Claude fixes it (that's why they're `require = 0`).

---

## Gate D — Visual (only once DLSS/jitter are live, later iterations)

Screenshots or a short clip while moving:
- Jitter sanity: with jitter on and DLSS off, a static scene should look **stable**, not
  shimmering. Violent shimmer ⇒ wrong jitter sign/scale ⇒ Claude flips `SIGN_X/SIGN_Y`
  or the NDC factor in `DlssJitter`.
- Later, DLSS on: ghosting/smearing on moving entities ⇒ motion-vector work.

**Paste back:** drag the screenshot into chat + one line on what you see.

---

## What Claude needs from THIS iteration

1. **Gate A** output (the four `javap` dumps) — unblocks locking all descriptors.
2. **Gate B** result after Claude adjusts from Gate A.
3. **Gate C** `grep` output once it builds.

Paste each as you get it — you don't have to do all gates before replying.

---

## Handy: reduce log noise

If `latest.log` is huge, this trims to what matters:
```bash
grep -nE "DLSSmc|Mixin|Vulkan|Exception|ERROR" run/logs/latest.log | head -100
```

---

## Committing / git workflow

**On your own machine (Windows/normal):** just use git normally.
```
git add -A
git commit -m "feat(P1-7): ..."
```
The `/tmp` workaround below is **only** needed inside a Claude sandbox session, because the
Cowork folder mount blocks file *deletion*, which breaks an in-place `.git`.

**Inside a Claude sandbox session:**
1. Git database lives at `/tmp/dlssmc.git`; the work-tree is the mounted folder. Point every
   git call at both:
   ```
   export GIT_DIR=/tmp/dlssmc.git GIT_WORK_TREE=/sessions/<id>/mnt/dlssmc
   git add -A
   git commit -m "<type>(<scope>): <summary>"
   ```
2. **Export a bundle after each commit** (the `/tmp` git-dir is wiped between sessions):
   ```
   git bundle create /tmp/dlssmc-history.bundle --all
   cp /tmp/dlssmc-history.bundle ./dlssmc-history.bundle
   ```
3. **Restore** next session or on your machine: `git clone dlssmc-history.bundle` (delete any
   broken `.git/` stub first — works on Windows; the mount can't).

**Critical lesson — write committed files via the shell, not only the Write/Edit tools.**
The mount is eventually-consistent: a file written by the assistant's tools may not have
propagated when `git commit` reads it, so git can capture a stale/truncated version or a
stray NUL from an in-place `sed`. For anything committed: author it with a shell heredoc, and
verify with `git show HEAD:<path>`, brace balance, and `grep -aqP '\x00' <path>` (no NUL).

**Commit message convention:** `type(scope): summary` — type ∈ feat|fix|docs|chore|debug,
scope = task id/area (e.g. P1-5, build). Body: what changed + why / what was verified.
