# Performance Analysis & Proposals

## Top 3 Detractors

### 1. Terrain velocity prepass — doubles opaque geometry (biggest hit)

`DlssTerrainVelocity.renderPrepass()` replays **every OPAQUE chunk draw call** a second
time into the RG16F velocity target with a depth test. Same vertex count, same draw
count as the real terrain render — resolution doesn't matter, it's pure geometry
throughput. This effectively doubles the opaque-geometry GPU load.

**Fix:** Replace with a single depth-sampled fullscreen pass. The depth buffer
already encodes which surface won at each pixel. Sample it, unproject with the
current view-proj inverse, reproject with previous view-proj. Correct per-pixel
velocity for all opaque + cutout surfaces, same vertex cost as the existing
fullscreen fallback (one triangle).

### 2. Full pipeline barriers — serialise the GPU (3–5 per frame)

`DlssEvaluator` uses `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` barriers between every
stage (world render → DLSS evaluate → copy → hand/HUD). Each waits for ALL prior
GPU work before anything after starts. No overlap.

**Fix:** Narrow to stage-specific barriers.
| Where | Wait on | Before |
|-------|---------|--------|
| After world render | `COLOR_ATTACHMENT_OUTPUT \| EARLY_FRAGMENT_TESTS` | DLSS reads depth+color |
| After DLSS evaluate | `COMPUTE_SHADER` | Copy reads output |
| After copy | `TRANSFER` | Hand/HUD reads native target |

### 3. Transient command buffer submit

DLSS work allocates a separate `VkCommandBuffer` via
`allocateAndBeginTransientCommandBuffer()`, fills it, ends it, then
`encoder.execute(cb)`. This flushes Mojang's open buffer and issues a new
driver submit — extra batch overhead.

**Fix:** Record DLSS commands into a **secondary command buffer**
(`VK_COMMAND_BUFFER_LEVEL_SECONDARY`) and inject via `vkCmdExecuteCommands` into
Mojang's existing primary buffer. Same batch, no extra submit. If DLSS fails,
the secondary buffer simply isn't executed.

## Secondary hits

- **Full-frame copies** — `copyOutputToNative` blits the entire DLSS output
  (STORAGE → native). With FG, 3 more copies for stable snapshots. Each is a
  full-resolution VRAM blit. Mostly bandwidth-bound.
- **Transient image creation** — `ensureOutputImage` allocates raw Vulkan images
  on resize. Negligible per-frame but can cause hitches on dynamic resolution.
