package foundry.imgui.mixin.renderer.v2;



import com.mojang.renderpearl.api.textures.GpuTextureView;
import foundry.imgui.api.ImGuiTextureProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "com.mojang.renderpearl.backend.common.BaseGpuTextureView", remap = false)
public class GpuTextureViewMixin implements ImGuiTextureProvider {

    @Unique
    private long imguimc$id;

    @Override
    public long imguimc$id() {
        return this.imguimc$id;
    }

    @Override
    public void imguimc$setId(final long id) {
        this.imguimc$id = id;
    }
}

