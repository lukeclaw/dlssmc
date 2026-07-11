#version 330

// P1-7 slice 2 — terrain vertex shader with velocity (MRT) support.
// Copy of minecraft:core/terrain.vsh plus per-vertex current/previous clip positions.
//
// `pos` is camera-relative. The same world point relative to the PREVIOUS camera is
// pos + camDelta, and DlssPrevViewProjT = prevViewProj * translate(camDelta) folds that
// in (same matrix family as the slice-1 fullscreen pass, from DlssMotion). Both
// matrices are UNJITTERED — gl_Position keeps Mojang's (jittered) ProjMat, so DLSS
// gets jitter-free motion vectors while rasterization stays jittered, as required.
// Terrain is static, so camera-only reprojection here is exact per-pixel (real
// geometry, real depth — this replaces slice 1's ASSUMED_DEPTH approximation).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(std140) uniform DlssReprojection {
    mat4 DlssCurViewProj;   // unjittered current view-proj
    mat4 DlssPrevViewProjT; // prevViewProj * translate(camDelta)
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec4 dlssCurrClip;
out vec4 dlssPrevClip;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    dlssCurrClip = DlssCurViewProj * vec4(pos, 1.0);
    dlssPrevClip = DlssPrevViewProjT * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    #else
    vertexColor = Color;
    #endif
    texCoord0 = UV0;
}
