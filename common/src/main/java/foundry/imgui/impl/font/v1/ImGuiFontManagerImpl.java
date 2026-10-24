package foundry.imgui.impl.font.v1;



import foundry.imgui.api.ImGuiMC;
import foundry.imgui.impl.ImGuiMCImpl;
import foundry.imgui.impl.font.ImGuiFontManager;
import foundry.imgui.impl.platform.ImGuiMCPlatform;
import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public class ImGuiFontManagerImpl implements ImGuiFontManager {

    private static final FileToIdConverter FONT_LISTER = new FileToIdConverter("imgui_font", ".ttf");
    private static final DecimalFormat FONT_FORMAT = new DecimalFormat("0.#");

    private Map<Identifier, FontPackBuilder> fontBuilders;
    private Map<Identifier, FontPack> fonts;
    private ImFont defaultFont;

    public ImGuiFontManagerImpl() {
        this.fontBuilders = Map.of();
        this.fonts = Map.of();
    }

    @Override
    public ImFont getFont(@Nullable final Identifier name, final boolean bold, final boolean italic) {
        if (name == null) {
            return this.defaultFont;
        }

        final FontPack font = this.fonts.get(name);
        if (font == null) {
            return this.defaultFont;
        }

        if (italic ^ bold) {
            return italic ? font.italic : font.bold;
        } else {
            return italic ? font.boldItalic : font.regular;
        }
    }

    
    @NotNull
    @Override
    public CompletableFuture<Void> reload(
            final @NotNull SharedState sharedState,
            final @NotNull Executor executor,
            final @NotNull PreparationBarrier preparationBarrier,
            final @NotNull Executor applyExecutor) {
        final ResourceManager resourceManager = sharedState.resourceManager();
        
    








        return CompletableFuture.supplyAsync(() -> {
            final Map<Identifier, FontData> fontData = new HashMap<>();

            for (final Map.Entry<Identifier, Resource> entry : FONT_LISTER.listMatchingResources(resourceManager).entrySet()) {
                final Identifier id = FONT_LISTER.fileToId(entry.getKey());
                final Resource resource = entry.getValue();
                try (final InputStream stream = resource.open()) {
                    final short[] ranges = resource.metadata().getSection(ImGuiFontRangesMetadataSection.TYPE)
                            .map(ImGuiFontRangesMetadataSection::ranges)
                            .orElse(ImGuiFontManager.DEFAULT_FONT_RANGES);
                    final float size = resource.metadata().getSection(ImGuiFontSizeSectionSerializer.TYPE)
                            .map(ImGuiFontSizeSectionSerializer::size)
                            .orElse(ImGuiFontManager.DEFAULT_FONT_SIZE);
                    fontData.put(id, new FontData(stream.readAllBytes(), ranges, size));
                } catch (final IOException e) {
                    ImGuiMCImpl.LOGGER.error("Failed to load ImGui font: {}", id, e);
                }
            }

            return fontData;
        }, executor).thenCompose(preparationBarrier::wait).thenAcceptAsync(fontData -> {
            final Map<Identifier, FontPackBuilder> fontBuilders = new HashMap<>();

            this.fontBuilders.values().forEach(FontPackBuilder::free);
            this.fontBuilders = Map.of();
            for (final Map.Entry<Identifier, FontData> entry : fontData.entrySet()) {
                final Identifier id = entry.getKey();
                final String[] parts = id.getPath().split("-", 2);
                if (parts.length < 2) {
                    continue;
                }

                final Identifier name = Identifier.tryBuild(id.getNamespace(), parts[0]);
                if (name == null) {
                    ImGuiMCImpl.LOGGER.error("Invalid font name: {}:{}", id.getNamespace(), parts[0]);
                    continue;
                }

                final String type = parts[1];
                switch (type) {
                    case "regular" -> fontBuilders.computeIfAbsent(name, FontPackBuilder::new).main = entry.getValue();
                    case "italic" -> fontBuilders.computeIfAbsent(name, FontPackBuilder::new).italic = entry.getValue();
                    case "bold" -> fontBuilders.computeIfAbsent(name, FontPackBuilder::new).bold = entry.getValue();
                    case "bold_italic" -> fontBuilders.computeIfAbsent(name, FontPackBuilder::new).boldItalic = entry.getValue();
                    default -> ImGuiMCImpl.LOGGER.warn("Unknown font type {} for font: {}", type, name);
                }
            }

            ImGuiMCImpl.LOGGER.info("Loaded {} ImGui fonts", fontBuilders.size());

            this.fontBuilders = Map.copyOf(fontBuilders);
            ImGuiMCImpl.handler.updateFonts();
        }, applyExecutor);
    }

    @Override
    public void rebuildFonts(final ImFontAtlas atlas) {
        float scale;
        try (final MemoryStack stack = MemoryStack.stackPush()) {
            final Map<Identifier, FontPack> fonts = new HashMap<>();

            atlas.clear();

            // SDL distinguishes monitor content scale from actual framebuffer pixel density.
            // The renderer already accounts for framebuffer density; font size stays in logical coordinates.
            final long window=net.minecraft.client.Minecraft.getInstance().getWindow().handle();
            final float density=org.lwjgl.sdl.SDLVideo.SDL_GetWindowPixelDensity(window);
            scale=Math.max(1.0F,ImGuiFontManager.getFontScale()/Math.max(1.0F,density));

            for (final Map.Entry<Identifier, FontPackBuilder> entry : this.fontBuilders.entrySet()) {
                ImGuiMCImpl.LOGGER.info("Built {}", entry.getKey());
                fonts.put(entry.getKey(), entry.getValue().build(scale));
            }

            this.fonts = Map.copyOf(fonts);
            this.defaultFont = this.getFont(ImGuiMC.FONT_DEFAULT, false, false);
            if (this.defaultFont == null) {
                ImGuiMCImpl.LOGGER.error("Failed to load default font: {}, using ImGui default", ImGuiMC.FONT_DEFAULT);
                this.defaultFont = atlas.addFontDefault();
            }

            ImGui.getIO().setFontDefault(this.defaultFont);
        }

        ImGuiMCPlatform.INSTANCE.registerImGuiFonts(atlas, this.defaultFont, scale);
    }

    @Override
    public void free() {
        // ImFontAtlas owns and destroys every ImFont and copied font-data buffer.
        // This manager only owns prepared Java data; destroying ImFont here double-frees the atlas.
        this.fontBuilders.values().forEach(FontPackBuilder::free);
        this.fontBuilders=Map.of();this.fonts=Map.of();this.defaultFont=null;
    }

    private record FontPack(ImFont regular,ImFont italic,ImFont bold,ImFont boldItalic) {}

    private static class FontPackBuilder implements NativeResource {

        private final Identifier name;
        private FontData main;
        private FontData italic;
        private FontData bold;
        private FontData boldItalic;

        private FontPackBuilder(final Identifier name) {
            this.name = name;
        }

        private ImFont loadOrDefault(@Nullable final FontData data, final String type, final float scale, final ImFont defaultFont) {
            if (data == null) {
                return defaultFont;
            } else {
                final ImFontConfig fontConfig = new ImFontConfig();
                final float sizePixels = data.size * scale;
                fontConfig.setGlyphRanges(data.ranges);
                fontConfig.setName(this.name.getPath() + " " + type + " " + FONT_FORMAT.format(sizePixels) + " px");
                fontConfig.setPixelSnapH(true);
                try{return ImGui.getIO().getFonts().addFontFromMemoryTTF(data.bytes, sizePixels, fontConfig);}
                finally{fontConfig.destroy();}
            }
        }

        public FontPack build(final float scale) {
            final ImFont main = Objects.requireNonNull(this.loadOrDefault(this.main, "regular", scale, null));
            final ImFont italic = this.loadOrDefault(this.italic, "italic", scale, main);
            final ImFont bold = this.loadOrDefault(this.bold, "bold", scale, main);
            final ImFont boldItalic = this.loadOrDefault(this.boldItalic, "bold_italic", scale, bold);
            return new FontPack(main, italic, bold, boldItalic);
        }

        @Override
        public void free() {
            this.main = null;
            this.italic = null;
            this.bold = null;
            this.boldItalic = null;
        }
    }

    private record FontData(byte[] bytes, short[] ranges, float size) {
    }
}
