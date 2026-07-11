#version 330

// Translucent velocity overlay (toggled by /dlssmc mv), drawn with ALPHA BLENDING over
// the finished native image. Outputs only tint + opacity — it does NOT re-draw the
// scene. (The first version re-sampled the half-res level texture as the background,
// which softened the image and ghosted stale/mismatched scene content "through"
// opaque blocks — Gate D 2026-07-10.)
//
// LOG-SCALED: walking parallax is a few px/frame, a mouse flick 10-50x that; a log
// curve keeps both visible. Direction maps to hue axes (R = +X, G = +Y around
// 0.5-gray), log-magnitude drives color strength and opacity.

uniform sampler2D InSampler; // RG16F velocity

in vec2 texCoord;

out vec4 fragColor;

const float MAX_OPACITY = 0.45;

void main() {
    vec2 v = texture(InSampler, texCoord).rg;

    float len = length(v);
    // ~0.0005 UV (sub-pixel) begins to register; ~0.06 UV (fast flick) tops out.
    float strength = clamp(log2(1.0 + len * 2048.0) / 7.0, 0.0, 1.0);
    vec2 dir = len > 1e-6 ? v / len : vec2(0.0);

    vec3 viz = vec3(0.5 + dir.x * 0.5 * strength, 0.5 + dir.y * 0.5 * strength, 0.5);
    fragColor = vec4(viz, MAX_OPACITY * strength);
}
