package foundry.imgui.impl.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import foundry.imgui.api.ImGuiSampler;
import foundry.imgui.api.ImGuiTextureProvider;
import imgui.ImDrawData;
import imgui.ImGui;
import imgui.flag.ImGuiConfigFlags;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.NativeResource;

@ApiStatus.Internal
public interface ImGuiRenderer extends NativeResource {

    void init();

    void newFrame();

    void renderDrawData(ImDrawData drawData, RenderTarget mainRenderTarget);

    default void renderPlatformWindows(final RenderTarget mainRenderTarget) {
        if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            ImGui.updatePlatformWindows();
            ImGui.renderPlatformWindowsDefault();
        }
    }

    default void postDraw() {
    }

    /** Run only after this frame's real GPU draws were submitted or discarded. */
    void afterDraw(Runnable action);

    void discard();

    void recreateFontsTexture();

    long getImGuiId(ImGuiTextureProvider texture, @Nullable ImGuiSampler sampler);
}
