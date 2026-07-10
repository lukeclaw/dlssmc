# DLSSmc

A Fabric mod that adds **NVIDIA DLSS (Super Resolution)** to Minecraft Java Edition by
piggybacking on Mojang's experimental Vulkan renderer (`renderpearl`), via SpongePowered
Mixin. Targets `26.3-snapshot-3`.

> Research prototype. Not shippable yet — DLSS binary redistribution is gated on the
> NVIDIA Streamline license (see `docs/`).

## Status

Rendering foundation is built and runtime-verified on real hardware (NVIDIA / Vulkan 1.4):

- Vulkan device + queue capture (for Streamline manual hooking)
- Sub-pixel projection jitter on the world (Halton 2,3)
- Resolution decoupling — 3D world renders at reduced internal res and upscales to
  native, HUD stays crisp (press **F8** to cycle native / Quality / Performance)
- Custom renderpearl shader pipeline
- Motion vectors: math done; GPU pass pending (see the guide)
- NVIDIA Streamline / DLSS wiring: Phase 2 (pending)

See **docs/PROJECT_TRACKER.md** (live status), **docs/IMPLEMENTATION_GUIDE.md** (resumable
spec), **docs/SPIKE_FINDINGS.md** (research), and **docs/VERIFY.md** (build/test loop).

## Build & run

Requires **JDK 25** and an NVIDIA RTX GPU with a Vulkan 1.2+ driver.

```
./gradlew genSources   # decompiled MC source for reference (first time)
./gradlew build        # compile
./gradlew runClient    # launch; then Video Settings -> Graphics -> "Prefer Vulkan"
```

In-game, load a world and press **F8** to cycle the internal render scale.

## License

Mod code: CC0 (from the Fabric example-mod template). NVIDIA Streamline / DLSS components
(added in Phase 2) carry NVIDIA's own SDK license — read it before distributing.
