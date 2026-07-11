package com.jhp.client.dlss;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Duck interface implemented by {@code GameRendererMixin} (P2-3) so the DLSS evaluate
 * hook in {@code LevelRendererMixin} can restore the native main render target EARLY —
 * right after recording slEvaluateFeature at {@code LevelRenderer.render} RETURN.
 *
 * <p>Early restore means renderItemInHand and the HUD render at NATIVE resolution on top
 * of the DLSS output (crisper than the old low-res hand), and Mojang's mid-renderLevel
 * depth clear hits the native depth instead of the level depth DLSS just consumed.
 * Restoring also nulls the saved target, which naturally skips the legacy NEAREST blit
 * in GameRendererMixin's renderLevel RETURN handler.</p>
 */
public interface DlssTargetAccess {

    /** The real native target saved during resolution decoupling, or null. */
    RenderTarget dlssmc$savedNativeTarget();

    /** Restore {@code mainRenderTarget} to the saved native target and clear the save. */
    void dlssmc$restoreNativeTarget();
}
