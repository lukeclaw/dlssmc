# DLSSmc

A Fabric mod that adds **NVIDIA DLSS (Super Resolution)** to Minecraft Java Edition by
piggybacking on Mojang's experimental Vulkan renderer (`renderpearl`), via SpongePowered
Mixin. Targets `26.3-snapshot-3`.

> Research prototype. Not shippable yet — DLSS binary redistribution is gated on the
> NVIDIA Streamline license (see `docs/`).

## Status

**DLSS IS LIVE** (2026-07-11): DLSS Super Resolution upscales the world in-game —
1280x685 → 2560x1369 (MaxPerformance) verified on an RTX 4070 SUPER, driver 596.49,
Vulkan 1.4. Current phase: M5 quality tuning (ghosting/artifact iteration).

All layers runtime-verified on real hardware:

- Vulkan device + queue capture; Streamline v2.12.0 hooked via its manual-hooking
  creation proxies (`SlBridge`, a Java 25 FFM binding of `sl.interposer.dll` — no JNI)
- Sub-pixel projection jitter on the world (Halton 2,3)
- Resolution decoupling — world renders at reduced internal res; DLSS outputs into a
  dedicated STORAGE image copied to the native target; hand + HUD render at native res
- Motion vectors — exact per-pixel terrain MVs (draw-replay prepass) + camera fallback
  for sky/entities (`/dlssmc mv` overlay)
- Per-frame SL plumbing — constants, frame-based resource tags, `slEvaluateFeature`
  replacing the old NEAREST upscale blit; automatic fallback to the blit if SL fails

In-game commands: `/dlssmc dlss` (toggle/status), `/dlssmc sl` (Streamline status),
`/dlssmc mv` (MV overlay), `/dlssmc scale` (render scale).

See **docs/PROJECT_TRACKER.md** (live status), **docs/IMPLEMENTATION_GUIDE.md** (resumable
spec), **docs/SPIKE_FINDINGS.md** (research), and **docs/VERIFY.md** (build/test loop).

## Build & run

Requires **JDK 25** and an NVIDIA RTX GPU with a Vulkan 1.2+ driver.

```
./gradlew genSources   # decompiled MC source for reference (first time)
./gradlew build        # compile
./gradlew runClient    # launch; then Video Settings -> Graphics -> "Prefer Vulkan"
```

Render scale is set by `DlssResolution.scale` (default 0.5 = Performance). An F8
keybind to cycle it live is a documented TODO in `DLSSmcClient` (pending the current
Fabric key-mapping API package).

## License

Mod code: CC0 (from the Fabric example-mod template). NVIDIA Streamline / DLSS components
(added in Phase 2) carry NVIDIA's own SDK license — read it before distributing.
