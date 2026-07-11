#version 330

// P1-7 slice 2 — terrain velocity PREPASS fragment shader.
// Single color output: screen-space velocity (the prepass renders into the RG16F
// velocity target only). Clip positions are interpolated and divided HERE
// (perspective-correct); ALPHA_CUTOUT discard runs first so leaves/grass get correct
// velocity coverage. Same convention as slice 1: UV space, current -> previous,
// (prevNDC - currNDC) * 0.5.

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:texture_sampling.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;
in vec4 dlssCurrClip;
in vec4 dlssPrevClip;

out vec4 fragVelocity;

void main() {
    #ifdef ALPHA_CUTOUT
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    vec2 currNdc = dlssCurrClip.xy / dlssCurrClip.w;
    vec2 prevNdc = dlssPrevClip.xy / dlssPrevClip.w;
    fragVelocity = vec4((prevNdc - currNdc) * 0.5, 0.0, 1.0);
}
