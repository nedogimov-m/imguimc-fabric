package foundry.imgui.impl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import foundry.imgui.api.ImGuiMC;
import foundry.imgui.impl.platform.ImGuiMCPlatform;
import imgui.ImGui;
import imgui.ImGuiViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class ImGuiMCImpl {

    public static final Logger LOGGER = LoggerFactory.getLogger(ImGuiMC.MOD_ID);

    public static ImGuiHandler handler;

    public static final ImGuiCharSink IM_GUI_CHAR_SINK = new ImGuiCharSink();
    public static final StringSplitter IM_GUI_SPLITTER = new StringSplitter((charId, style) -> {
        // TODO in future versions get the font data from the built font
        try (final ImGuiMC.ActiveContext context = ImGuiMC.withImGui()) {
            if (context == null) {
                return 0.0F;
            }

            ImGui.pushFont(ImGuiMC.getStyleFont(style), 0.0F);
            float width = ImGui.calcTextSizeX(Character.toString(charId));
            ImGui.popFont();
            return width;
        }
    });

    public static void init() {
    }

    public static void initHandler() {
        try {
            handler = new ImGuiHandler(Minecraft.getInstance().getWindow());
            ImGuiMCPlatform.INSTANCE.afterImGuiLoad();
        } catch (final Throwable t) {
            LOGGER.error("Failed to load ImGui, disabling", t);
            handler = null;
        }
    }

    public static Identifier path(final String path) {
        return Identifier.fromNamespaceAndPath(ImGuiMC.MOD_ID, path);
    }

    public static RenderTarget getMainRenderTarget() {
        
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
         
        

    }

    public static long getWindowHandle(final Window window) {
        
        return window.handle();
        
        

    }

    public static TextureTarget createRenderTarget(final int width, final int height, final boolean depth) {
        
        return new TextureTarget("ImGui", width, height, com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, depth ? com.mojang.renderpearl.api.GpuFormat.D32_FLOAT : null);
         
        

        




        




    }

    public static void resizeRenderTarget(final RenderTarget renderTarget, final int width, final int height) {
        
        renderTarget.resize(width, height);
        
        

    }

    public static float getContentScaleForMonitor(final long monitor) {
        if (handler == null) {
            return 1.0F;
        }

        return handler.getWindowHandler().getContentScaleForMonitor(monitor);
    }

    @Contract("null,_->null")
    public static <T extends ImGuiWindowHandler.RenderViewportData> @Nullable T getRenderData(final ImGuiViewport vp, final Supplier<T> factory) {
        if (handler == null) {
            return null;
        }

        return handler.getWindowHandler().getRenderData(vp, factory);
    }
}
