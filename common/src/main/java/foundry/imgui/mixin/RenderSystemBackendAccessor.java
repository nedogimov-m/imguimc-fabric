package foundry.imgui.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSystem.class)
public interface RenderSystemBackendAccessor {
    @Accessor("BACKEND")
    static GpuBackend imguimc$getBackend() { throw new AssertionError("Mixin accessor was not applied"); }
}
