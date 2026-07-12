# P2-6 — Streamline SDK license notes (FIRST-PASS, NOT LEGAL ADVICE)

> Generated 2026-07-11 by an AI agent reading the local SDK license files
> (`license.txt`, `bin/x64/nvngx_dlss.license.txt`, `reflex.license.txt`,
> `3rd-party-licenses.md`). This is a planning aid for the human P2-6 read —
> verify every quoted clause against the actual files before relying on it.

## What's actually in the SDK tree (there isn't one license)

| Component | File | License type |
|---|---|---|
| Streamline core (headers, `sl.interposer.dll`, `sl.common.dll`, `sl.nis.dll`, `source/`) | `license.txt` (root) | **MIT** (Copyright 2023 NVIDIA) |
| `nvngx_dlss.dll`, `nvngx_dlssd.dll`, `nvngx_dlssg.dll`, `nvngx_deepdvc.dll` | `bin/x64/nvngx_dlss.license.txt` (= `external/ngx-sdk/license.txt`) | **"NVIDIA RTX SDKs LICENSE" (v. March 14, 2024)** — proprietary EULA |
| `sl.reflex.dll` | `bin/x64/reflex.license.txt` | "LICENSE AGREEMENT FOR NVIDIA SDKs" (Dec 9, 2020) + Reflex supplement — proprietary |
| `sl.nvperf.dll` | referenced in root `license.txt` | NSight Perf SDK License (separate PDF) |

`sl.interposer.dll` (the hooking layer the mod loads) is MIT — freely redistributable.
**The restrictive terms live in the NGX/DLSS binaries** (`nvngx_dlss.dll` etc.).

## 1. Redistribution

- §1(c): may "Distribute any software and materials within the SDK ... as incorporated
  in object code format into a software application subject to the distribution
  requirements" → bundling `nvngx_dlss.dll` in the mod is explicitly permitted, subject to:
- §2 Distribution Requirements: (a) app must have "material additional functionality";
  (b) source-mod notice "This software contains source code provided by NVIDIA
  Corporation."; (c) downstream terms at least as protective; (d) notify NVIDIA of
  known non-compliant distribution.
- §4(b): "you may not distribute or sublicense the SDK as a stand-alone product" —
  no bare-DLL distribution.
- The license does NOT distinguish development vs production binaries — that's an
  engineering convention. README: "Only use `production` builds when releasing".
  Ship `bin/x64/`, not `bin/x64/development/`.
- §4(e) no-copyleft-taint: "You may not use the SDK in any manner that would cause it
  to become subject to an open source software license." → risk if the mod is
  GPL/AGPL; "mere aggregation" argument needs a lawyer if copyleft is chosen.

## 2. Attribution / notices

- §7.1(b) (mandatory): "you must attribute the use of the applicable SDK and include
  the NVIDIA Marks on splash screens, in the about box of the application (if
  present), and in credits for game applications." → mod-info page / README / about
  entry with NVIDIA DLSS marks.
- §4(a): no implying NVIDIA sponsorship/endorsement without separate agreement.
- §7.2(c)/§7.3(b): trademark license is narrow; no combining marks / confusingly
  similar marks without written approval.

## 3. Restrictions that could bite

- §4(a)/(d): no reverse engineering / circumvention of the SDK itself. The interposer
  hooking pattern is the SDK's documented, intended integration mechanism — not RE of
  NVIDIA code. Hooking MINECRAFT is outside this license entirely (Mojang EUL A/modding
  policy question).
- "Application" scope gap: license says nothing about rights to the host game being
  modified. Biggest open question; separate from NVIDIA terms.
- Supplement §6.2: NVIDIA reserves the right "as a last resort, to temporarily disable
  the DLSS integration" if a public release has material quality issues left unfixed.
- Supplement §4: "required to notify NVIDIA prior to commercial release of an
  application (including a plug-in to a commercial application)" via
  https://developer.nvidia.com/sw-notification — Minecraft is commercial; the mod is
  arguably a plug-in to it. Low-cost to just send the notification.
- Supplement §1: DLSS licensed "only for ... use in systems with NVIDIA GPUs".
- Supplement §2: content sent to NVIDIA for support grants them a perpetual license
  (careful: Minecraft footage is Mojang IP).
- §10/§11: AS-IS, liability capped at US$10.
- No non-commercial exception anywhere — a $0 mod is treated like a commercial product.

## 4. OTA / telemetry

- Supplement §6.1: "you agree that NVIDIA can make over-the-air updates of the SDK in
  systems that have the SDK installed" — driver-level NGX may silently update the DLSS
  model on users' machines; worth disclosing in the mod description.
- No explicit telemetry clause found in the local license files.

## 5. Practical verdict (not legal advice)

| Shipping model | Read |
|---|---|
| Bundle production DLLs in the mod download | Clearly permitted per §1(c)/§2 with: production builds, unmodified NVIDIA-signed DLLs, attribution (§7.1(b)), no endorsement implication, send the notification email. |
| Fetch-on-install | Also permitted; same obligations; slightly lower distribution exposure. |
| User-supplied DLLs | Lowest licensing burden (mod never distributes NVIDIA binaries); worst UX. |
| Copyleft mod license + bundled DLLs | Flagged risk (§4(e)); ask a lawyer. |
| Hooking Minecraft itself | Not addressed by NVIDIA's license at all — Mojang EULA question. |

**Ask a lawyer:** (1) copyleft + §4(e) interaction; (2) whether Supplement §4
notification is triggered by a free mod ("plug-in to a commercial application");
(3) Mojang EULA and renderer hooking; (4) whether a mod-info screen satisfies §7.1(b).
