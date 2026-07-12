package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssMipBias;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MV-Q detail pass: renderpearl hardcodes {@code mipLodBias(0.0F)} for every sampler
 * ({@code VulkanGpuSampler} ctor). While {@link DlssMipBias} is armed — i.e. during
 * {@code LevelRenderer.render}, whose only sampler creation is the terrain
 * chunk-layer sampler — substitute the DLSS LOD bias {@code log2(scale)+extra} so
 * terrain mips are chosen for the DISPLAY resolution, not the reduced render
 * resolution. All other samplers (SamplerCache, mip-generation) keep vanilla 0.
 */
@Mixin(VulkanGpuSampler.class)
public abstract class VulkanGpuSamplerMixin {

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSamplerCreateInfo;mipLodBias(F)Lorg/lwjgl/vulkan/VkSamplerCreateInfo;",
                    remap = false),
            require = 1)
    private VkSamplerCreateInfo dlssmc$biasMipLod(VkSamplerCreateInfo createInfo, float vanillaBias) {
        if (DlssMipBias.isArmed()) {
            float bias = DlssMipBias.effectiveBias();
            if (bias != 0f) {
                DlssMipBias.logApplied(bias);
                return createInfo.mipLodBias(bias);
            }
        }
        return createInfo.mipLodBias(vanillaBias);
    }
}
