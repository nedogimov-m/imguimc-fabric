package foundry.imgui.mixin.renderer.v2;



import com.mojang.renderpearl.api.textures.GpuSampler;
import foundry.imgui.api.ImGuiSampler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = {"com.mojang.renderpearl.backend.opengl.GlSampler", "com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler"}, remap = false)
public class GpuSamplerMixin implements ImGuiSampler {
}


