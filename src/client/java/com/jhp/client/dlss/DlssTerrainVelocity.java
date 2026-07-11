package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Terrain velocity PREPASS (P1-7 slice 2, Approach B).
 *
 * <p>After the world renders (and after the slice-1 fullscreen pass fills the velocity
 * target with a camera-only fallback), OPAQUE terrain (SOLID + CUTOUT layers) is drawn
 * a second time into the RG16F velocity target, replaying the exact per-frame draw data
 * Mojang prepared — {@link ChunkSectionsToRender} is a record, so its atlas view, draw
 * groups, and per-section uniform slices are all public. The level target's depth
 * buffer is attached with the vanilla reverse-Z compare (GEQUAL) and depth writes OFF:
 * identical vertex transforms produce identical depth, so exactly the visible terrain
 * pixels pass, giving precise per-pixel motion vectors with correct occlusion — and no
 * Mojang pipeline or render pass is modified.</p>
 *
 * <p>The pipeline derives from {@code TERRAIN_SNIPPET} (same vertex format, bind
 * groups, defines infrastructure) with our shaders, a single RG16F color target, and an
 * extra {@code DlssReprojection} UBO (two mat4s: unjittered current view-proj, and
 * prev view-proj with the camera delta folded in — from {@link DlssMotion}).
 * {@code ALPHA_CUTOUT=0.5} is set for the whole group: solid-layer alpha is 1.0, so the
 * discard only bites on cutout geometry (leaves/grass), matching vanilla coverage.</p>
 *
 * <p>Entities/translucents keep the slice-1 camera-only fallback until P1-8.</p>
 */
public final class DlssTerrainVelocity {
    private DlssTerrainVelocity() {}

    private static final int UBO_SIZE = 128; // two std140 mat4s

    /** Per-frame draw data captured at LevelRenderer.executeSolid HEAD; consumed once. */
    private static ChunkSectionsToRender capturedDraws;

    private static BindGroupLayout reprojectionLayout;
    private static RenderPipeline pipeline;
    private static GpuBuffer uboBuffer;
    private static GpuBufferSlice uboSlice;
    private static boolean loggedOnce;

    /** Called from {@code LevelRendererMixin} each frame with the live draw data. */
    public static void captureFrame(ChunkSectionsToRender draws) {
        capturedDraws = draws;
    }

    /**
     * Replay OPAQUE terrain into {@code velocityTarget} using {@code levelTarget}'s
     * depth. Runs at renderLevel RETURN, after the fullscreen camera-velocity pass.
     */
    public static void renderPrepass(TextureTarget velocityTarget, RenderTarget levelTarget) {
        ChunkSectionsToRender draws = capturedDraws;
        capturedDraws = null;
        if (draws == null || velocityTarget == null || levelTarget == null) {
            return;
        }
        GpuTextureView velocityView = velocityTarget.getColorTextureView();
        GpuTextureView depthView = levelTarget.getDepthTextureView();
        if (velocityView == null || depthView == null) {
            return;
        }

        writeUbo();
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(pipeline());
        if (!loggedOnce) {
            loggedOnce = true;
            DLSSmc.LOGGER.info("[DLSSmc] terrain velocity prepass active: {}x{} compiled={}",
                    velocityTarget.width, velocityTarget.height, compiled);
        }

        // Index buffer handling mirrors ChunkSectionsToRender.renderLayers.
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = draws.maxIndicesRequired() == 0 ? null : autoIndices.getBuffer();
        IndexType indexType = draws.maxIndicesRequired() == 0 ? null : autoIndices.type();

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "DLSS terrain velocity prepass", velocityView,
                        Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass); // Projection (jittered), Fog, Globals, Lighting
            pass.setUniform("DlssReprojection", uboSlice);
            pass.bindTexture("Sampler0", draws.textureView(),
                    RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
            pass.bindTexture("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setPipeline(compiled);

            for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
                Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroup =
                        draws.drawGroupsPerLayer().get(layer);
                if (drawGroup == null) {
                    continue;
                }
                for (List<RenderPass.Draw<GpuBufferSlice[]>> list : drawGroup.values()) {
                    if (!list.isEmpty()) {
                        pass.drawMultipleIndexed(list, indexBuffer, indexType,
                                List.of("ChunkSection"), draws.chunkSectionInfos());
                    }
                }
            }
        }
    }

    private static void writeUbo() {
        if (uboBuffer == null) {
            uboBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "DLSS reprojection UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UBO_SIZE);
            uboSlice = uboBuffer.slice(0, UBO_SIZE);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = Std140Builder.onStack(stack, UBO_SIZE)
                    .putMat4f(DlssMotion.currentViewProj())
                    .putMat4f(DlssMotion.previousViewProjTranslated())
                    .get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uboSlice, bytes);
        }
    }

    private static BindGroupLayout reprojectionLayout() {
        if (reprojectionLayout == null) {
            reprojectionLayout = BindGroupLayout.builder()
                    .withUniform("DlssReprojection", UniformType.UNIFORM_BUFFER)
                    .build();
        }
        return reprojectionLayout;
    }

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("dlssmc", "pipeline/terrain_velocity"))
                    .withVertexShader(Identifier.fromNamespaceAndPath("dlssmc", "core/terrain_velocity"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("dlssmc", "core/terrain_velocity"))
                    .withShaderDefine("ALPHA_CUTOUT", 0.5F)
                    .withBindGroupLayout(reprojectionLayout())
                    .withColorTargetState(new ColorTargetState(
                            Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_ALL))
                    // Vanilla reverse-Z compare, but no depth writes: equal-depth terrain
                    // passes; everything else is occluded correctly.
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build();
        }
        return pipeline;
    }
}
