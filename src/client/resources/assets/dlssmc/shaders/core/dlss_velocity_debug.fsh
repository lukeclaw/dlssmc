#version 330

// Translucent velocity overlay (toggled by /dlssmc mv): the scene (low-res level
// target, Sampler0) is composited under the velocity field (Sampler1).
//
// LOG-SCALED: real motion magnitudes span ~3 orders of magnitude (walking parallax is
// a few px/frame; a mouse flick is 10-50x that), so linear amplification either hides
// walking or saturates flicks. Direction maps to hue axes (R = +X, G = +Y around
// 0.5-gray), log-magnitude drives both color strength and overlay opacity.

uniform sampler2D Sampler0; // scene color (level target)
uniform sampler2D Sampler1; // RG16F velocity

in vec2 texCoord;

out vec4 fragColor;

const float MAX_OPACITY = 0.45;

void main() {
    vec3 scene = texture(Sampler0, texCoord).rgb;
    vec2 v = texture(Sampler1, texCoord).rg;

    float len = length(v);
    // ~0.0005 UV (sub-pixel) begins to register; ~0.06 UV (fast flick) tops out.
    float strength = clamp(log2(1.0 + len * 2048.0) / 7.0, 0.0, 1.0);
    vec2 dir = len > 1e-6 ? v / len : vec2(0.0);

    vec3 viz = vec3(0.5 + dir.x * 0.5 * strength, 0.5 + dir.y * 0.5 * strength, 0.5);
    float opacity = MAX_OPACITY * strength;

    fragColor = vec4(mix(scene, viz, opacity), 1.0);
}
