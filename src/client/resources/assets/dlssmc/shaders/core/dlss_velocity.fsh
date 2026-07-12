#version 330

// Depth-aware fullscreen velocity pass (replaces the old camera-only fallback + terrain
// geometry prepass). Samples the level depth buffer for per-pixel accurate reprojection.
//
// ProjMat is the full reprojection matrix (prevViewProjTranslated * curInvViewProj),
// computed in DlssMotion. For each pixel, the actual depth from the depth buffer is used
// instead of an assumed far-plane value, giving correct velocity for all opaque and
// cutout surfaces. Sky pixels (depth ≈ 0 in REVERSE-Z) naturally get far-plane velocity.
//
// The homogeneous-scaling trick still holds: ProjMat * vec4(ndc, depth, 1.0) = prevClip
// with correct perspective because the intermediate division by clipW cancels out.
// See DlssVelocity.java for the CPU-side setup.

layout(std140) uniform Projection {
    mat4 ProjMat; // reprojection matrix (prevViewProjTranslated * curInvViewProj)
};

uniform sampler2D InSampler; // depth buffer (D32_FLOAT, REVERSE-Z: 1=near, ~0=far)

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    float depth = texture(InSampler, texCoord).r;

    vec4 prevClip = ProjMat * vec4(ndc, depth, 1.0);
    if (prevClip.w <= 0.0) {
        // Behind the previous camera: no valid history.
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 prevNdc = prevClip.xy / prevClip.w;

    // UV-space velocity (NDC delta * 0.5). Sign/space vs DLSS convention tuned in P2-3.
    fragColor = vec4((prevNdc - ndc) * 0.5, 0.0, 1.0);
}
