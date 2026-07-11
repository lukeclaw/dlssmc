#version 330

// Translucent velocity overlay (toggled by /dlssmc mv): the scene (low-res level
// target, Sampler0) is composited under the amplified velocity field (Sampler1).
// Overlay opacity scales with motion magnitude, so a static camera shows an almost
// clean scene and motion "lights up" — red/green = X/Y direction, brightness = speed.

uniform sampler2D Sampler0; // scene color (level target)
uniform sampler2D Sampler1; // RG16F velocity

in vec2 texCoord;

out vec4 fragColor;

// Lower amplification + opacity cap than the first iteration: at AMPLIFY=20 /
// max 0.65 a fast strafe saturated near terrain into a wash that read as
// "seeing through the floor" (Gate D feedback).
const float AMPLIFY = 8.0;
const float MIN_OPACITY = 0.05;
const float MAX_OPACITY = 0.45;

void main() {
    vec3 scene = texture(Sampler0, texCoord).rgb;
    vec2 v = texture(Sampler1, texCoord).rg;

    vec3 viz = vec3(0.5 + v.x * AMPLIFY, 0.5 + v.y * AMPLIFY, 0.5);
    float opacity = clamp(length(v) * AMPLIFY * 2.0, MIN_OPACITY, MAX_OPACITY);

    fragColor = vec4(mix(scene, viz, opacity), 1.0);
}
