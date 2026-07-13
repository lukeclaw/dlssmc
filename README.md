# DLSSmc

**DLSS Super Resolution is LIVE. Frame Generation is in quality tuning. 84 commits, one dev (me), one AI agent (Claude), one research spike (DeepSeek).**

I'm **lukeclaw** — this is my research project to bring NVIDIA DLSS to Minecraft Java Edition.
It works by piggybacking on Mojang's experimental Vulkan renderer (`renderpearl`) via SpongePowered
Mixin, with NVIDIA's Streamline SDK loaded through Java 25 FFM (Panama) — no JNI.

The goal is simple: make Minecraft run at high internal resolutions with AI upscaling instead of
native rendering. Roughly 18 months ago this wasn't possible (Java Edition was OpenGL-only).
Mojang's deobfuscation in 26.1 + Vulkan renderer in 26.2 changed that, and this mod is the result.

[![License: CC0-1.0](https://img.shields.io/badge/license-CC0--1.0-blue.svg)](#license)
[![Minecraft](https://img.shields.io/badge/minecraft-26.3--snapshot--3-green)](https://www.minecraft.net)
[![Java](https://img.shields.io/badge/java-25%2B-orange)](https://jdk.java.net/25/)
[![Fabric](https://img.shields.io/badge/mod%20loader-Fabric-dbd0b4)](https://fabricmc.net)
[![Build](https://github.com/lukeclaw/dlssmc/actions/workflows/build.yml/badge.svg)](https://github.com/lukeclaw/dlssmc/actions/workflows/build.yml)

---

## Features

| Feature | Status | Notes |
|---------|--------|-------|
| DLSS Super Resolution (SR) | ✅ **LIVE** | World renders at reduced internal res → DLSS upscales to native |
| Sub-pixel jitter | ✅ **LIVE** | Halton(2,3) sequence for DLSS temporal feedback |
| Resolution decoupling | ✅ **LIVE** | World at internal res, HUD/UI at native res |
| Motion vectors | ✅ **LIVE** | Depth-aware fullscreen velocity pass + camera fallback |
| Mip LOD bias | ✅ **LIVE** | Automatic terrain mip bias correction at reduced scales |
| DLSS Frame Generation (FG) | 🔄 **Tuning** | DLSS-G enabled; quality pass in progress |
| Reflex + PCL markers | ✅ **LIVE** | Latency measurement markers for FG |
| In-game tuning panel | ✅ **LIVE** | Press **K** to open the DLSSmc tuning panel |
| Benchmark suite | ✅ **LIVE** | `/dlssmc bench` for automated performance testing |

---

## Requirements

- **Minecraft:** Java Edition `26.3-snapshot-3` with the **Vulkan renderer** enabled
  (Video Settings → Graphics → **Prefer Vulkan (Experimental)**)
- **GPU:** NVIDIA RTX series with Vulkan 1.2+ driver
- **Java:** JDK 25
- **Mod loader:** Fabric Loader ≥0.19.3 + Fabric API
- **SDK:** NVIDIA Streamline SDK v2.12.0 (see [setup](#setup) below)

---

## Quick Start

### 1. Clone & build

```bash
./gradlew genSources   # decompile Minecraft sources (first time only)
./gradlew build        # build the mod
```

### 2. Set up Streamline SDK

DLSS requires NVIDIA's Streamline SDK, which is **not bundled** due to licensing. You'll need to:

1. Download [Streamline SDK v2.12.0](https://developer.nvidia.com/rtx/streamline) from NVIDIA
2. Extract it to `streamline-sdk-v2.12.0/` in the project root
3. The build expects the following files under that directory:
   - `bin/x64/sl.interposer.dll`
   - `bin/x64/nvngx_dlss.dll`
   - `bin/x64/nvngx_dlssg.dll`
   - `bin/x64/sl.reflex.dll`

### 3. Run

```bash
./gradlew runClient
```

In-game: ensure **Prefer Vulkan** is ON, then press **K** to open the tuning panel
or use the commands below.

---

## Commands

| Command | Description |
|---------|-------------|
| `/dlssmc dlss` | Toggle DLSS Super Resolution on/off |
| `/dlssmc fg` | Toggle DLSS Frame Generation on/off |
| `/dlssmc sl` | Show Streamline SDK status |
| `/dlssmc mv` | Toggle motion vector debug overlay |
| `/dlssmc scale` | Cycle render scale (Native → 0.667 → 0.5) |
| `/dlssmc mode` | Cycle DLSS mode (Auto, MaxPerf, Balanced, MaxQuality, DLAA) |
| `/dlssmc preset` | Cycle DLSS preset override (Default, K, L, M) |
| `/dlssmc bias` | Cycle mip LOD bias offset |
| `/dlssmc mvx` / `/dlssmc mvy` | Flip motion vector sign X/Y |
| `/dlssmc jx` / `/dlssmc jy` | Flip jitter sign X/Y |
| `/dlssmc bench` | Start/stop benchmark run |

> **K key** — Opens the non-pausing DLSSmc tuning panel for quick A/B comparison.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Minecraft (renderpearl)                  │
│  ┌──────────────┐   ┌──────────────┐   ┌─────────────────┐ │
│  │ GameRenderer  │   │ LevelRenderer│   │  VulkanBackend   │ │
│  │ (jitter/res)  │   │ (MV pass,    │   │  (vkCreate*)     │ │
│  │               │   │  DLSS eval)  │   │                  │ │
│  └──────┬───────┘   └──────┬───────┘   └────────┬────────┘ │
│         │                  │                     │          │
│         ▼                  ▼                     ▼          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Mixin Injection Layer                    │   │
│  │  GameRendererMixin  LevelRendererMixin  Vulkan*Mixin │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
└─────────────────────────┼───────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  SlBridge     │  │  DlssJitter  │  │ DlssEvaluator│
│ (FFM Panama   │  │  Halton(2,3) │  │ (per-frame   │
│  → sl.inter-  │  │  sequence    │  │  SL pipeline)│
│  poser.dll)   │  │              │  │              │
└──────┬───────┘  └──────────────┘  └──────┬───────┘
       │                                    │
       ▼                                    ▼
┌──────────────────────────────────────────────────────────┐
│              NVIDIA Streamline SDK (sl.interposer.dll)     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │ DLSS-SR  │  │ DLSS-FG  │  │  Reflex  │  │   PCL    │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘ │
└──────────────────────────────────────────────────────────┘
```

### Key components

| Component | File | Role |
|-----------|------|------|
| **SlBridge** | `src/client/.../dlss/SlBridge.java` | Java 25 FFM (Panama) binding — loads `sl.interposer.dll` directly, no JNI |
| **DlssEvaluator** | `src/client/.../dlss/DlssEvaluator.java` | Per-frame DLSS pipeline: token → constants → tag resources → `slEvaluateFeature` |
| **DlssJitter** | `src/client/.../dlss/DlssJitter.java` | Halton(2,3) sub-pixel jitter sequence for temporal feedback |
| **DlssMotion** | `src/client/.../dlss/DlssMotion.java` | Camera state tracking + reprojection matrix math |
| **DlssVelocity** | `src/client/.../dlss/DlssVelocity.java` | Fullscreen depth-aware velocity pass (RG16F motion vectors) |
| **DlssResolution** | `src/client/.../dlss/DlssResolution.java` | Resolution decoupling — world at internal scale, HUD at native |
| **DlssMipBias** | `src/client/.../dlss/DlssMipBias.java` | Automatic mip LOD bias correction for reduced render scales |
| **DlssBenchmark** | `src/client/.../dlss/DlssBenchmark.java` | Automated benchmark suite |
| **DlssTuningScreen** | `src/client/.../DlssTuningScreen.java` | In-game tuning panel (K key) |

---

## Project Structure

```
├── src/
│   ├── main/                         # Common mod code
│   │   ├── java/com/jhp/
│   │   │   ├── DLSSmc.java           # Mod entrypoint
│   │   │   └── mixin/ExampleMixin.java
│   │   └── resources/
│   ├── client/                       # Client-side code
│   │   ├── java/com/jhp/client/
│   │   │   ├── DLSSmcClient.java     # Client init + commands
│   │   │   ├── DlssTuningScreen.java # In-game tuning panel
│   │   │   ├── dlss/                 # DLSS core engine
│   │   │   │   ├── SlBridge.java     # FFM → Streamline bridge
│   │   │   │   ├── DlssEvaluator.java
│   │   │   │   ├── DlssJitter.java
│   │   │   │   ├── DlssMotion.java
│   │   │   │   ├── DlssVelocity.java
│   │   │   │   ├── DlssResolution.java
│   │   │   │   ├── DlssMipBias.java
│   │   │   │   ├── DlssRenderState.java
│   │   │   │   ├── DlssDebug.java
│   │   │   │   ├── DlssBenchmark.java
│   │   │   │   └── DlssTargetAccess.java
│   │   │   └── mixin/                # Mixin injection targets
│   │   │       ├── VulkanInstanceMixin.java
│   │   │       ├── VulkanBackendMixin.java
│   │   │       ├── VulkanDeviceMixin.java
│   │   │       ├── VulkanGpuSurfaceMixin.java
│   │   │       ├── VulkanGpuSamplerMixin.java
│   │   │       ├── GameRendererMixin.java
│   │   │       ├── LevelRendererMixin.java
│   │   │       └── MinecraftMixin.java
│   │   └── resources/
│   │       ├── assets/dlssmc/
│   │       │   ├── lang/en_us.json
│   │       │   └── shaders/core/
│   │       │       ├── dlss_velocity.fsh
│   │       │       ├── dlss_velocity_debug.fsh
│   │       │       └── dlss_depth_debug.fsh
│   │       └── dlssmc.client.mixins.json
│   └── main/resources/
│       ├── assets/dlssmc/icon.png
│       ├── fabric.mod.json
│       └── dlssmc.mixins.json
├── docs/                            # Documentation
│   ├── PRD.md                       # Product requirements
│   ├── PROJECT_TRACKER.md           # Live status & task tracker
│   ├── IMPLEMENTATION_GUIDE.md      # Architecture & implementation spec
│   ├── FRAMEGEN_BRIEF.md            # DLSS Frame Generation spec
│   ├── SPIKE_FINDINGS.md            # Research findings
│   ├── PERFORMANCE.md               # Performance analysis
│   ├── LICENSE_NOTES.md             # NVIDIA license analysis
│   ├── VERIFY.md                    # Build/test iteration guide
│   └── architecture_sequence.puml   # UML architecture sequence diagram
├── gradle/                          # Gradle wrapper
├── build.gradle                     # Build configuration
├── settings.gradle
├── gradle.properties
├── .github/workflows/build.yml     # CI
└── LICENSE                          # CC0-1.0
```

---

## Documentation

The [`docs/`](./docs/) directory contains detailed engineering documentation:

- [Project Tracker](./docs/PROJECT_TRACKER.md) — live status, task tracker, decision log
- [PRD](./docs/PRD.md) — product requirements & scope
- [Implementation Guide](./docs/IMPLEMENTATION_GUIDE.md) — architecture, file map, gotchas
- [Frame Generation Brief](./docs/FRAMEGEN_BRIEF.md) — DLSS-G implementation spec
- [Spike Findings](./docs/SPIKE_FINDINGS.md) — research & empirical evidence
- [Performance](./docs/PERFORMANCE.md) — performance analysis & optimization notes
- [License Notes](./docs/LICENSE_NOTES.md) — NVIDIA Streamline/DLSS license review
- [Verify Guide](./docs/VERIFY.md) — build/test iteration loop

---

## License

- **Mod source code:** [CC0 1.0 Universal](./LICENSE) (public domain)
- **NVIDIA Streamline SDK & DLSS binaries:** NVIDIA proprietary SDK license — see
  [`docs/LICENSE_NOTES.md`](./docs/LICENSE_NOTES.md) and the Streamline SDK's own
  license before redistributing.

---

*Built with [Fabric Loom](https://fabricmc.net/wiki/documentation:fabric_loom),
[SpongePowered Mixin](https://github.com/SpongePowered/Mixin), and
[NVIDIA Streamline](https://developer.nvidia.com/rtx/streamline).*
