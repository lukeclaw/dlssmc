#version 330

// P1-7 slice 2 — terrain velocity PREPASS vertex shader.
// Same transforms as minecraft:core/terrain.vsh (identical gl_Position, so depth-test
// GEQUAL against the level's reverse-Z depth buffer passes exactly on visible terrain),
// plus per-vertex current/previous clip positions from UNJITTERED matrices.
//
// `pos` is camera-relative. The same world point relative to the PREVIOUS camera is
// pos + camDelta; DlssPrevViewProjT = prevViewProj * translate(camDelta) folds that in
// (same matrices as the slice-1 fullscreen pass, from DlssMotion). Terrain is static,
// so camera-only reprojection here is exact per-pixel — real geometry, real depth.

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

layout(std140) uniform DlssReprojection {
    mat4 DlssCurViewProj;   // unjittered current view-proj
    mat4 DlssPrevViewProjT; // prevViewProj * translate(camDelta)
};

out vec4 vertexColor;
out vec2 texCoord0;
out vec4 dlssCurrClip;
out vec4 dlssPrevClip;

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    dlssCurrClip = DlssCurViewProj * vec4(pos, 1.0);
    dlssPrevClip = DlssPrevViewProjT * vec4(pos, 1.0);

    // Alpha is all the FS needs (cutout discard), but keep the vanilla expression so
    // the pipeline's SAMPLER2 bind-group slot is referenced.
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
