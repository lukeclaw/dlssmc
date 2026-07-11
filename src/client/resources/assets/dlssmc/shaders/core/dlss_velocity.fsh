#version 330

// P1-7 Approach B, slice 1 — camera-only velocity, full-screen pass.
//
// ProjMat here is NOT a projection: it is the full reprojection matrix computed on the
// CPU (DlssMotion.reprojectionMatrix): prevViewProj · translate(camDelta) · inv(curViewProj).
// We reuse vanilla's Projection block/layout (BindGroupLayouts.PROJECTION) so no custom
// UBO is needed. Applying it to the pixel's current clip position yields the
// previous-frame clip position; velocity = (prevNDC - currNDC) * 0.5 in UV space,
// pointing from the current pixel to where it was last frame.
//
// The intermediate perspective divide of the un-projection cancels (homogeneous scale
// invariance), so folding the chain into one matrix is exact. Exact for camera rotation
// (depth-independent); approximate for translation. Terrain pixels are overwritten with
// exact geometry velocity by the slice-2 prepass; this fallback covers sky/entities/
// translucents.

layout(std140) uniform Projection {
    mat4 ProjMat; // reprojection matrix (see above)
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;

    // REVERSE-Z: NDC z ~ near/dist, so a SMALL z means "far away". 0.0002 with
    // near=0.05 is ~250 blocks — right for what this fallback covers (sky, distant
    // unprepassed pixels). Rotation is depth-independent; this only bounds the
    // translation error. The original 0.5 meant ~0.1 blocks -> rainbow sky (Gate D).
    // MUST match DlssVelocity.ASSUMED_DEPTH.
    vec4 prevClip = ProjMat * vec4(ndc, 0.0002, 1.0);
    if (prevClip.w <= 0.0) {
        // Behind the previous camera: no valid history.
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 prevNdc = prevClip.xy / prevClip.w;

    // UV-space velocity (NDC delta * 0.5). Sign/space vs DLSS convention tuned in P2-3.
    fragColor = vec4((prevNdc - ndc) * 0.5, 0.0, 1.0);
}
