import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.*;
import foundry.imgui.api.ImGuiMC;
import foundry.imgui.api.ImGuiMCEvents;
import foundry.imgui.impl.ImGuiMCImpl;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Uses the installed ImGuiMC handler, actual SDL event watch and native host rendering. */
public final class FoundationImGuiChecks {
    private static final AtomicBoolean active = new AtomicBoolean();
    private static final AtomicInteger frames = new AtomicInteger(), clicks = new AtomicInteger();
    private static final ImString text = new ImString(256);
    private static volatile float buttonX, buttonY, textX, textY;
    private static volatile Throwable drawFailure;
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static GpuSampler sampler;

    public static void run(ClientGameTestContext test, TestSingleplayerContext world,
            Map<String,Object> report, List<String> checks) throws Exception {
        var result = new LinkedHashMap<String,Object>(); report.put("imguimc", result);
        result.put("scope", "Installed production handler, native Minecraft pre/post rendering, actual SDL window events, clicked widgets, Unicode text, live resource/font rebuild and deferred image retirement. No replacement production classes or duplicate platform initialization.");
        world.getServer().runCommand("gamemode creative @a");
        test.runOnClient(client -> {
            check(ImGuiMCImpl.handler != null, "Installed ImGuiMC handler missing");
            client.setScreenAndShow(null); client.mouseHandler.releaseMouse();
            var device = RenderSystem.getDevice();
            texture = device.createTexture("Foundation audit typed image", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            view = device.createTextureView(texture);
            sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0));
            try (var stack = MemoryStack.stackPush()) {
                var bytes = stack.malloc(4); bytes.put(new byte[]{0, (byte)255, 0, (byte)255}).flip();
                var encoder = device.createCommandEncoder(); encoder.writeToTexture(texture, bytes, 0, 0, 0, 0, 1, 1); encoder.submit();
            }
            ImGuiMCEvents.INSTANCE.preRenderImGuiEvent(FoundationImGuiChecks::draw);
            active.set(true);
        });
        try {
            waitFrames(test, 5);
            result.put("initialScreenshot", FoundationClientAudit.captureComposited(test, "foundation-imgui-initial-composition").toString());
            test.runOnClient(client -> {
                try (var context = ImGuiMC.withImGui()) {
                    check(context != null, "Native context unavailable after host frames");
                    check(ImGuiMC.getFont(ImGuiMC.FONT_DEFAULT, false, false) != null, "Native default font unavailable");
                    result.put("displaySize", List.of(context.io().getDisplaySizeX(), context.io().getDisplaySizeY()));
                    result.put("framebufferScale", List.of(context.io().getDisplayFramebufferScaleX(), context.io().getDisplayFramebufferScaleY()));
                    result.put("hostImagePixel", imagePixel(client));
                }
            });
            checks.add("Installed ImGuiMC host hooks render an actual typed green image into the native main target; exact RGBA pixel readback passed");

            // Warp the genuine SDL cursor so both the event queue and native polling see the same position.
            warp(test, buttonX, buttonY); button(test, true); waitFrames(test, 2); button(test, false); waitFrames(test, 3);
            check(clicks.get() == 1, "A native SDL click must activate exactly one ImGui button; observed " + clicks.get());
            result.put("nativeButtonClicks", clicks.get());
            warp(test, textX, textY); button(test, true); waitFrames(test, 2); button(test, false); waitFrames(test, 3);
            test.runOnClient(client -> {
                try (var context = ImGuiMC.withImGui()) { check(context.io().getWantTextInput(), "Clicked native text field did not acquire text input"); }
            });
            final String expected = "audit \u03bb\u0416";
            var utf8 = new AtomicReference<ByteBuffer>();
            try {
                test.runOnClient(client -> {
                    var bytes = MemoryUtil.memUTF8(expected); utf8.set(bytes);
                    try (var stack = MemoryStack.stackPush()) {
                        var event = SDL_Event.calloc(stack); event.type(SDLEvents.SDL_EVENT_TEXT_INPUT);
                        event.text().windowID(SDLVideo.SDL_GetWindowID(client.getWindow().handle())).text(bytes);
                        check(SDLEvents.SDL_PushEvent(event), "SDL rejected native Unicode input");
                    }
                });
                test.waitFor(client -> expected.equals(text.get()) || drawFailure != null, 200);
                check(expected.equals(text.get()), "Native Unicode input changed: " + text.get());
            } finally { test.runOnClient(client -> { if (utf8.get() != null) MemoryUtil.memFree(utf8.get()); }); }
            result.put("nativeTypedText", text.get());
            checks.add("Actual SDL cursor/button events activate the real widget; UTF-8 lambda/Cyrillic passes through the production SDL watch into the clicked native ImGui field");
            result.put("screenshot", FoundationClientAudit.captureComposited(test, "foundation-imgui-native-input-and-image").toString());

            var reload = new AtomicReference<CompletableFuture<Void>>();
            test.runOnClient(client -> reload.set(client.reloadResourcePacks()));
            test.waitFor(client -> reload.get() != null && reload.get().isDone(), 600);
            test.runOnClient(client -> reload.get().join());
            test.waitFor(client -> client.gui.overlay() == null, 300); waitFrames(test, 5);
            test.runOnClient(client -> {
                try (var context = ImGuiMC.withImGui()) {
                    check(context != null && ImGuiMC.getFont(ImGuiMC.FONT_DEFAULT, false, false) != null, "Live resource reload lost native fonts");
                    result.put("imageAfterReload", imagePixel(client));
                }
                check(expected.equals(text.get()), "Live reload reset an active native text field");
            });
            result.put("reloadScreenshot", FoundationClientAudit.captureComposited(test, "foundation-imgui-after-native-resource-reload").toString());
            result.put("nativeFrames", frames.get()); result.put("status", "passed");
            checks.add("Live Minecraft resource reload rebuilds installed fonts while native input text and typed image rendering survive");
        } finally {
            active.set(false); waitFramesOrTicks(test);
            var retired = new AtomicBoolean();
            test.runOnClient(client -> ImGuiMC.afterDraw(() -> {
                view.close(); texture.close(); sampler.close(); retired.set(true);
            }));
            test.waitFor(client -> retired.get(), 200);
            result.put("deferredResourcesRetired", retired.get());
            test.runOnClient(client -> { try (var context = ImGuiMC.withImGui()) { if (context != null) ImGui.setWindowFocus(null); } client.mouseHandler.grabMouse(); });
        }
    }

    private static void draw() {
        if (!active.get()) return;
        try {
            ImGui.setNextWindowPos(50, 70, ImGuiCond.Once); ImGui.setNextWindowSize(440, 230, ImGuiCond.Once);
            ImGui.begin("Foundation ImGui native audit", ImGuiWindowFlags.NoSavedSettings);
            ImGui.text("Native full production stack: lambda \u03bb, Cyrillic \u0416");
            if (ImGui.button("Native SDL button")) clicks.incrementAndGet();
            buttonX = (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2;
            buttonY = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2;
            ImGui.inputText("Unicode input", text);
            textX = ImGui.getItemRectMinX() + 20; textY = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2;
            ImGui.text("Actual native image below; resource reload preserves this field"); ImGui.end();
            long image = ImGuiMCImpl.handler.getRenderer().getImGuiId(ImGuiMC.getTexture(view), ImGuiMC.getSampler(sampler));
            ImGui.getForegroundDrawList().addImage(image, 10, 10, 32, 32);
            frames.incrementAndGet();
        } catch (Throwable failure) { drawFailure = failure; throw failure; }
    }

    private static List<Integer> imagePixel(Minecraft client) {
        var target = client.gameRenderer.mainRenderTarget();
        var color = target.getColorTexture();
        check(color.getFormat() == GpuFormat.RGBA8_UNORM, "Expected native main RGBA8 target");
        int x = (int)(20 * target.width / ImGui.getIO().getDisplaySizeX());
        // Same bottom-left RenderTarget storage used by vanilla GUI and native surface blit.
        int y = target.height - 1 - (int)(20 * target.height / ImGui.getIO().getDisplaySizeY());
        var device = RenderSystem.getDevice();
        try (var buffer = device.createBuffer(() -> "Foundation native ImGui pixel", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 4)) {
            var commands = device.createCommandEncoder(); commands.copyTextureToBuffer(color, buffer, 0, () -> {}, 0, x, y, 1, 1);
            try (var fence = commands.createFence()) { commands.submit(); check(fence.awaitCompletion(10_000_000_000L), "Native image readback timed out"); }
            try (var mapped = buffer.map(true, false)) {
                var p = mapped.data(); var rgba = List.of(p.get(0)&255, p.get(1)&255, p.get(2)&255, p.get(3)&255);
                check(rgba.equals(List.of(0,255,0,255)), "Installed host image is absent or corrupted: " + rgba); return rgba;
            }
        }
    }
    private static void warp(ClientGameTestContext test, float x, float y) {
        test.runOnClient(client -> SDLMouse.SDL_WarpMouseInWindow(client.getWindow().handle(), x, y)); waitFrames(test, 3);
    }
    private static void button(ClientGameTestContext test, boolean down) {
        test.runOnClient(client -> { try (var stack = MemoryStack.stackPush()) {
            var event = SDL_Event.calloc(stack); event.type(down ? SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN : SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP);
            event.button().windowID(SDLVideo.SDL_GetWindowID(client.getWindow().handle())).button((byte)SDLMouse.SDL_BUTTON_LEFT).down(down);
            check(SDLEvents.SDL_PushEvent(event), "SDL rejected native mouse button");
        } });
    }
    private static void waitFrames(ClientGameTestContext test, int count) {
        int before = frames.get(); test.waitFor(client -> frames.get() >= before + count || drawFailure != null, 300);
        if (drawFailure != null) throw new AssertionError("Installed ImGui native draw failed", drawFailure);
    }
    private static void waitFramesOrTicks(ClientGameTestContext test) { test.waitTicks(3); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private FoundationImGuiChecks() {}
}
