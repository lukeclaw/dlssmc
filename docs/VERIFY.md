# How to help / verify — the iteration loop

Claude works in a sandbox with **no JDK 25, no GPU, no network**, so it cannot compile,
run, or see the game. You are the hands. Each iteration has up to four gates. Run the
ones Claude asks for, then paste the marked output back into chat.

> Windows: use **Git Bash** (ships with Git for Windows) so the commands below work
> as-is. In PowerShell, replace `./gradlew` with `.\gradlew.bat`. Run everything from
> the repo root: `D:\Projects\dlssmc`.

---

## Gate A — Confirm descriptors (do this FIRST, once per snapshot)

This replaces Claude's constant-pool guesses with the real signatures. Needs JDK 25's
`javap` (you have it now).

```bash
# find the deobfuscated Minecraft jar Loom already downloaded
JAR=$(find .gradle/loom-cache -name 'minecraft-clientOnly-*-26.3-snapshot-3.jar' | head -1)
echo "$JAR"

javap -p -cp "$JAR" com.mojang.renderpearl.backend.vulkan.VulkanDevice
javap -p -cp "$JAR" com.mojang.renderpearl.backend.vulkan.VulkanQueue
javap -p -cp "$JAR" net.minecraft.client.renderer.GameRenderer
javap -p -cp "$JAR" net.minecraft.client.renderer.Projection
```

**Paste back:** the full output of all four. From it Claude will pin:
- `VulkanDevice`: exact `<init>` signature + `vkDevice` field + queue getters (for `VulkanDeviceMixin`).
- `GameRenderer`: exact `renderLevel(...)` descriptor (for `GameRendererMixin`).
- `Projection`: exact `getMatrix()` name/return (for `ProjectionMixin`).

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
