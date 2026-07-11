#version 330

// Translucent velocity overlay (toggled by /dlssmc mv), drawn with ALPHA BLENDING over
// the finished native image. Outputs only tint + opacity — it does NOT re-draw the
// scene. (The first version re-sampled the half-res level texture as the background,
// which softened the image and ghosted stale/mismatched scene content "through"
// opaque blocks — Gate D 2026-07-10.)
//
// TWO LOG CURVES (tuned 2026-07-11 for stronger, more legible overlay):
// - colorStrength saturates FAST so medium-distance walking parallax (~50 blocks)
//   already reads at full color, matching what previously only the nearest blocks hit.
// - opacityStrength keeps the original, slower-climbing curve, so the very nearest
//   blocks (much higher raw magnitude) stay visibly more solid/opaque than
//   medium-distance ones even though both are now full color. MAX_OPACITY was also
//   raised so near blocks read stronger than the old baseline, not just relative to
//   medium ones.
// Direction still maps to hue axes (R = +X, G = +Y around 0.5-gray).
//
// If 50 blocks still looks washed out, lower COLOR_LOG_DIVISOR or raise
// COLOR_LOG_SCALE further. If near blocks don't feel strong enough, raise
// MAX_OPACITY (careful above ~0.8 — it starts occluding the scene).

uniform sampler2D InSampler; // RG16F velocity

in vec2 texCoord;

out vec4 fragColor;

const float MAX_OPACITY = 0.65;

// Color: steep — saturates ~4x sooner (in log-magnitude terms) than the opacity curve.
const float COLOR_LOG_SCALE = 8192.0;
const float COLOR_LOG_DIVISOR = 5.0;

// Opacity: original gentler curve — keeps climbing after color has already maxed out.
const float OPACITY_LOG_SCALE = 2048.0;
const float OPACITY_LOG_DIVISOR = 7.0;

void main() {
    vec2 v = texture(InSampler, texCoord).rg;

    float len = length(v);
    float colorStrength = clamp(log2(1.0 + len * COLOR_LOG_SCALE) / COLOR_LOG_DIVISOR, 0.0, 1.0);
    float opacityStrength = clamp(log2(1.0 + len * OPACITY_LOG_SCALE) / OPACITY_LOG_DIVISOR, 0.0, 1.0);
    vec2 dir = len > 1e-6 ? v / len : vec2(0.0);

    vec3 viz = vec3(0.5 + dir.x * 0.5 * colorStrength, 0.5 + dir.y * 0.5 * colorStrength, 0.5);
    fragColor = vec4(viz, MAX_OPACITY * opacityStrength);
}
