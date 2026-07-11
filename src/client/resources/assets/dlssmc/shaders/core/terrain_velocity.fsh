#version 330

// P1-7 slice 2 — terrain fragment shader with velocity (MRT) output.
// Copy of minecraft:core/terrain.fsh plus a second color output: screen-space velocity
// from perspective-correct interpolated clip positions (divide HERE, not in the VS —
// interpolating post-divide NDC across a triangle is wrong).
// Same convention as slice 1: UV-space, current -> previous, (prevNDC - currNDC) * 0.5.
// ALPHA_CUTOUT discard runs before the velocity write, so velocity gets correct
// coverage on leaves/grass for free.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:texture_sampling.glsl>
#moj_import <minecraft:oit.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 dlssCurrClip;
in vec4 dlssPrevClip;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif
layout(location = 1) out vec4 fragVelocity;

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif

    vec2 currNdc = dlssCurrClip.xy / dlssCurrClip.w;
    vec2 prevNdc = dlssPrevClip.xy / dlssPrevClip.w;
    fragVelocity = vec4((prevNdc - currNdc) * 0.5, 0.0, 1.0);
}
