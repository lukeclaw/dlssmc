package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Motion-vector support (Phase 1, tracker task P1-7).
 *
 * <p>This first slice captures the per-frame data a camera-reprojection motion-vector
 * pass needs — the <b>unjittered</b> view-projection and the world camera position, for
 * both the current and previous frame — and validates the reprojection MATH on the CPU
 * (logged) before it is committed to a GPU shader. Minecraft renders camera-relative, so
 * clip = proj · viewRot · (worldPos − cameraPos); reprojection must therefore carry the
 * camera-position delta between frames.</p>
 *
 * <p>The GPU consumer is {@code DlssVelocity} (depth-sampled fullscreen pass, single
 * folded reprojection matrix + level depth buffer).</p>
 */
public final class DlssMotion {
    private DlssMotion() {}

    private static final Matrix4f curViewProj = new Matrix4f();
    private static final Matrix4f prevViewProj = new Matrix4f();
    /** inverse(curViewProj), recomputed once per frame for the GPU velocity pass. */
    private static final Matrix4f curInvViewProj = new Matrix4f();
    /** prevViewProj · translate(camDelta) — camera delta folded in. */
    private static final Matrix4f prevViewProjTranslated = new Matrix4f();
    /**
     * Full reprojection in ONE matrix: prevViewProj · T(camDelta) · inverse(curViewProj).
     * Valid because perspective division is invariant under homogeneous scaling, so the
     * intermediate divide by rel.w cancels; prevNdc = project(reprojection · curClip).
     * One mat4 lets the GPU pass reuse ProjectionMatrixBuffer + the stock PROJECTION
     * bind-group layout (no custom UBO).
     */
    private static final Matrix4f reprojection = new Matrix4f();
    /** Unjittered projection + view rotation kept separately for the SL Constants (P2-3). */
    private static final Matrix4f curProj = new Matrix4f();
    private static final Matrix4f curViewRot = new Matrix4f();
    private static double curCamX, curCamY, curCamZ;
    private static double prevCamX, prevCamY, prevCamZ;
    private static boolean primed = false;
    private static long frame = 0;
    /** P2-4: level identity + jump threshold for camera-cut detection. */
    private static Object lastLevel;
    private static final double RESET_JUMP_SQ = 8.0 * 8.0; // blocks^2 per frame

    /** Capture this frame's unjittered view-proj + camera position; shift the previous. */
    public static void capture(CameraRenderState camera, Object level) {
        // P2-4: camera-cut detection. A level swap (portal/respawn/rejoin) or an
        // implausible one-frame jump (>8 blocks: teleport/pearl — legit movement is
        // <1 block/frame even sprint-elytra) means DLSS history is from a different
        // view. Request the reset flag and re-prime so prev==cur this frame (zero MVs,
        // consistent with the reset).
        if (primed) {
            double dx = camera.pos.x - curCamX;
            double dy = camera.pos.y - curCamY;
            double dz = camera.pos.z - curCamZ;
            if (level != lastLevel || dx * dx + dy * dy + dz * dz > RESET_JUMP_SQ) {
                DlssJitter.requestReset();
                primed = false;
                DLSSmc.LOGGER.info("[DLSSmc] camera cut ({}) — DLSS history reset requested",
                        level != lastLevel ? "level change" : "position jump");
            }
        }
        lastLevel = level;

        prevViewProj.set(curViewProj);
        prevCamX = curCamX; prevCamY = curCamY; prevCamZ = curCamZ;

        // clip = projection * viewRotation, applied to (worldPos - cameraPos).
        curProj.set(camera.projectionMatrix);
        curViewRot.set(camera.viewRotationMatrix);
        curViewProj.set(camera.projectionMatrix).mul(camera.viewRotationMatrix);
        curCamX = camera.pos.x; curCamY = camera.pos.y; curCamZ = camera.pos.z;

        if (!primed) {
            prevViewProj.set(curViewProj);
            prevCamX = curCamX; prevCamY = curCamY; prevCamZ = curCamZ;
            primed = true;
        }

        // Derived matrices for the GPU velocity pass (DlssVelocity):
        // prevRel = rel + camDelta  =>  prevClip = prevVP * T(camDelta) * rel.
        curInvViewProj.set(curViewProj).invert();
        prevViewProjTranslated.set(prevViewProj).translate(camDeltaX(), camDeltaY(), camDeltaZ());
        reprojection.set(prevViewProjTranslated).mul(curInvViewProj);

        frame++;
        debugSanityCheck();
    }

    public static Matrix4f currentProjection() { return curProj; }
    public static Matrix4f currentViewRotation() { return curViewRot; }
    public static double cameraX() { return curCamX; }
    public static double cameraY() { return curCamY; }
    public static double cameraZ() { return curCamZ; }
    public static Matrix4f currentViewProj() { return curViewProj; }
    public static Matrix4f previousViewProj() { return prevViewProj; }
    public static Matrix4f currentViewProjInverse() { return curInvViewProj; }
    public static Matrix4f previousViewProjTranslated() { return prevViewProjTranslated; }
    public static Matrix4f reprojectionMatrix() { return reprojection; }
    public static float camDeltaX() { return (float) (curCamX - prevCamX); }
    public static float camDeltaY() { return (float) (curCamY - prevCamY); }
    public static float camDeltaZ() { return (float) (curCamZ - prevCamZ); }

    /**
     * CPU validation of the exact reprojection the fullscreen shader does: for the
     * screen-centre pixel at the assumed fallback depth, reconstruct the world point
     * from the current view-proj and reproject it with the previous frame's matrices.
     * Logs the resulting motion vector in NDC when the camera actually moved, throttled
     * so it doesn't spam.
     */
    private static void debugSanityCheck() {
        float dx = camDeltaX(), dy = camDeltaY(), dz = camDeltaZ();
        boolean moved = (dx * dx + dy * dy + dz * dz) > 1.0e-8f;
        if (!moved || (frame % 30) != 0) {
            return;
        }
        // Centre pixel, NDC (0,0), at far-plane depth (REVERSE-Z: small z = far).
        // This validates the reprojection math for sky/distant pixels; terrain pixels
        // use the actual depth from the buffer on the GPU.
        Vector4f clip = new Vector4f(0f, 0f, 0.0002f, 1f);
        Vector4f rel = new Matrix4f(curViewProj).invert().transform(clip);
        if (rel.w == 0f) return;
        rel.div(rel.w); // camera-relative world point (relative to CURRENT camera)

        // Re-express relative to the PREVIOUS camera, then project with previous matrices.
        Vector4f prevRel = new Vector4f(rel.x + dx, rel.y + dy, rel.z + dz, 1f);
        Vector4f prevClip = prevViewProj.transform(prevRel);
        if (prevClip.w == 0f) return;
        float mvx = prevClip.x / prevClip.w - 0f; // current centre NDC.x is 0
        float mvy = prevClip.y / prevClip.w - 0f; // current centre NDC.y is 0
        DLSSmc.LOGGER.info("[DLSSmc] MV sanity (centre px): camDelta=({}, {}, {}) -> mvNDC=({}, {})",
                dx, dy, dz, mvx, mvy);
    }
}
