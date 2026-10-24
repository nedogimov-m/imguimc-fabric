import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import foundry.imgui.impl.ImGuiWindowHandler;
import foundry.imgui.impl.ImGuiWindowHandlerSDL3;
import imgui.*;
import imgui.callback.ImPlatformFuncViewport;
import imgui.flag.*;
import imgui.internal.ImGuiContext;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLKeyboard.*;
import static org.lwjgl.sdl.SDLMouse.*;
import static org.lwjgl.sdl.SDLScancode.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Tests the actual SDL platform with native ImGui and native surfaces, independently of the renderer. */
public final class NativeSdlPlatformAcceptance implements ClientModInitializer {
    private int ticks;
    private boolean ran;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ran || ++ticks < 5 || !client.isGameLoadFinished()) return;
            ran = true;
            Map<String, Object> report = new LinkedHashMap<>();
            var device = RenderSystem.getDevice();
            report.put("backend", device.getDeviceInfo().backendName());
            report.put("device", device.getDeviceInfo().name());
            report.put("sdlDriver", SDL_GetCurrentVideoDriver());
            try {
                run(client, report);
                report.put("status", "passed");
            } catch (Throwable failure) {
                failure.printStackTrace();
                report.put("status", "failed");
                report.put("error", failure.toString());
            } finally {
                try { Files.writeString(Path.of(System.getProperty("imguimc.sdl.report")), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                client.stop();
            }
        });
    }

    private static void run(Minecraft client, Map<String, Object> report) {
        ImGuiContext previous = ImGui.getCurrentContext();
        ImGuiContext context = ImGui.createContext();
        var frames = new AtomicLong();
        var platform = new ImGuiWindowHandlerSDL3(null, frames::get);
        var targets = new ArrayList<SurfaceTarget>();
        long main = client.getWindow().handle();
        int mainId = SDL_GetWindowID(main);
        String oldClipboard = SDLClipboard.SDL_GetClipboardText();
        boolean previousVisible = SDL_CursorVisible();
        long previousCursor = SDL_GetCursor();
        int detachedId = 0;
        try {
            var io = ImGui.getIO();
            io.setIniFilename(null);
            io.setConfigFlags(ImGuiConfigFlags.ViewportsEnable | ImGuiConfigFlags.NavEnableGamepad);
            io.setConfigInputTrickleEventQueue(false);
            io.getFonts().getTexDataAsRGBA32(new ImInt(), new ImInt());
            io.getFonts().setTexID(1);
            platform.init(client.getWindow(), true, ImGuiWindowHandler.ClientApi.VULKAN);
            io.addBackendFlags(ImGuiBackendFlags.RendererHasViewports);
            check(io.hasBackendFlags(ImGuiBackendFlags.PlatformHasViewports), "Actual SDL driver must support desktop viewport coordinates");
            check(ImGui.getMainViewport().getPlatformHandle() == main, "Main platform handle remains the real borrowed SDL window");
            check(ImGui.getMainViewport().getPlatformHandleRaw() != 0, "Main raw native OS window is exposed through SDL properties");
            check(ImGui.getPlatformIO().getMonitorsSize() > 0, "Actual monitor enumeration and work areas");
            check(platform.getContentScaleForMonitor(SDL_GetPrimaryDisplay()) > 0, "Native display DPI query");
            ImGui.setClipboardText("native SDL3 clipboard \u03bb \u0416");
            check("native SDL3 clipboard \u03bb \u0416".equals(ImGui.getClipboardText()), "Clipboard callback round trip through SDL");
            ImGui.getPlatformIO().setRendererRenderWindow(new ImPlatformFuncViewport() {
                @Override public void accept(ImGuiViewport vp) {
                    SurfaceTarget target = platform.getRenderData(vp, () -> { var created = new SurfaceTarget(); targets.add(created); return created; });
                    target.resize(vp.getPlatformHandle());
                    RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.target.getColorTexture(), new Vector4f(.125f, .5f, .75f, 1));
                }
            });
            ImString text = new ImString(128);
            for (int i = 0; i < 4; i++) frame(client, platform, frames, text, i == 0, false);
            ImGuiViewport detached = detached();
            check(detached != null, "Native ImGui creates an actual detached viewport");
            long window = detached.getPlatformHandle();
            detachedId = SDL_GetWindowID(window);
            check(window != 0 && window != main && detachedId != mainId, "Detached viewport owns a distinct SDL window");
            check(detached.getPlatformHandleRaw() != 0, "Detached raw OS window is genuine");
            check(platform.getSurface(detached) != null && platform.getSurface(detached).currentConfiguration().isPresent(), "Real detached native surface was configured");
            check(!platform.getSurface(detached).isAcquired(), "Surface acquisition is released by actual present after submit");
            check(targets.size() == 1 && targets.getFirst().target.getColorTextureView() != null, "Detached callback uses a genuine native texture target");

            // Push through SDL itself. The watch must copy all payloads before their source storage expires.
            pushKey(detachedId, true, 'a', SDL_SCANCODE_A, (short) 0);
            pushButton(detachedId, true, SDL_BUTTON_LEFT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var event = SDL_Event.calloc(stack);
                event.type(SDL_EVENT_MOUSE_WHEEL); event.wheel().windowID(detachedId).x(2).y(3).direction(SDL_MOUSEWHEEL_NORMAL);
                check(SDL_PushEvent(event), "SDL accepts wheel input");
            }
            // The native input widget must be focused before queued text is applied.
            frame(client, platform, frames, text, false, true);
            check(ImGui.isKeyDown(ImGuiKey.A), "Copied SDL key down reaches the native ImGui input queue");
            check(io.getMouseDown(0), "Copied SDL left button reaches the native ImGui input queue");
            pushKey(detachedId, false, 'a', SDL_SCANCODE_A, (short) 0);
            pushButton(detachedId, false, SDL_BUTTON_LEFT);
            frame(client, platform, frames, text, false, false);
            check(!ImGui.isKeyDown(ImGuiKey.A) && !io.getMouseDown(0), "SDL key and button releases clear native held state");
            frame(client, platform, frames, text, true, false);
            frame(client, platform, frames, text, false, false);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var event = SDL_Event.calloc(stack); event.type(SDL_EVENT_TEXT_INPUT);
                event.text().windowID(detachedId).text(stack.UTF8("\u03bb\u0416"));
                check(SDL_PushEvent(event), "SDL accepts Unicode text input");
                // SDL_PushEvent does not own this test's UTF-8 pointer; the production watch has copied it.
                SDL_FlushEvent(SDL_EVENT_TEXT_INPUT);
            }
            frame(client, platform, frames, text, false, false);
            check("\u03bb\u0416".equals(text.get()), "SDL Unicode bytes survive source storage expiry and reach the actual native input widget");
            check(ImGuiWindowHandlerSDL3.keyToImGuiKey('q', SDL_SCANCODE_A) == ImGuiKey.Q, "Keyboard layout keycode controls shortcut identity");
            check(ImGuiWindowHandlerSDL3.keyToImGuiKey(0, SDL_SCANCODE_KP_1) == ImGuiKey.Keypad1, "Keypad identity survives NumLock");

            SDL_SetWindowSize(window, 211, 137);
            SDL_PumpEvents();
            frame(client, platform, frames, text, false, false);
            var config = platform.getSurface(detached()).currentConfiguration().orElseThrow();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var w = stack.mallocInt(1); var h = stack.mallocInt(1); SDL_GetWindowSizeInPixels(window, w, h);
                check(config.width() == w.get(0) && config.height() == h.get(0), "Resize reconfigures surface to actual native pixel size");
                check(targets.getFirst().target.width == w.get(0) && targets.getFirst().target.height == h.get(0), "Viewport render target follows actual SDL pixel dimensions");
            }
            pushKey(detachedId, true, 'b', SDL_SCANCODE_B, (short) 0);
            frame(client, platform, frames, text, false, false);
            check(ImGui.isKeyDown(ImGuiKey.B), "Owner key is held before detached window retirement");
            ImGui.destroyPlatformWindows();
            check(SDL_GetWindowFromID(detachedId) == 0, "Destroy platform windows retires the genuine detached SDL window");
            check(SDL_GetWindowFromID(mainId) == main, "Borrowed Minecraft SDL window survives detached retirement");
            check(targets.stream().allMatch(target -> target.closed), "All native per-viewport targets are released");
            platform.newFrame(client.gameRenderer.mainRenderTarget()); ImGui.newFrame();
            check(!ImGui.isKeyDown(ImGuiKey.B), "Retired window releases its owned held key without a later SDL event");
            ImGui.endFrame();
            platform.shutdown();
            check(SDL_GetWindowFromID(mainId) == main, "Platform shutdown preserves borrowed SDL owner");
            check(SDL_GetCursor() == previousCursor && SDL_CursorVisible() == previousVisible, "Platform shutdown restores the original actual cursor and visibility");
            check(!io.hasBackendFlags(ImGuiBackendFlags.PlatformHasViewports), "Platform capabilities removed at shutdown");
            report.put("detachedWindowId", detachedId);
            report.put("nativeSurfaceSize", List.of(config.width(), config.height()));
            report.put("gamepad", io.hasBackendFlags(ImGuiBackendFlags.HasGamepad) ? "physical device connected" : "no physical device; connection path uncertified");
            report.put("checks", List.of("native ImGui context and transformed RenderSystem backend accessor", "actual borrowed SDL main window and native detached viewport/OS handle", "actual display/work area/DPI and Unicode clipboard", "SDL-pushed copied key/button/wheel events/releases and Unicode native input widget", "native surface configure/acquire/blit/submit/present and pixel-size resize", "detached retirement releases held key, targets, surface and SDL window", "main SDL owner/cursor survive shutdown"));
        } finally {
            platform.shutdown();
            if (oldClipboard != null) SDLClipboard.SDL_SetClipboardText(oldClipboard);
            ImGui.destroyContext(context);
            ImGui.setCurrentContext(previous);
        }
    }

    private static void frame(Minecraft client, ImGuiWindowHandlerSDL3 platform, AtomicLong frames, ImString text, boolean focus, boolean checkInput) {
        frames.incrementAndGet();
        platform.newFrame(client.gameRenderer.mainRenderTarget());
        ImGui.newFrame();
        if (checkInput) {
            var io = ImGui.getIO();
            check(Math.abs(io.getMouseWheel() - 3) < .001f && Math.abs(io.getMouseWheelH() + 2) < .001f, "SDL wheel direction and horizontal convention");
        }
        if (frames.get() == 1) {
            var main = ImGui.getMainViewport();
            ImGui.setNextWindowPos(main.getPosX() + main.getSizeX() + 48, main.getPosY() + 40);
            ImGui.setNextWindowSize(173, 111);
        }
        if (focus) ImGui.setNextWindowFocus();
        ImGui.begin("SDL native detached", ImGuiWindowFlags.NoSavedSettings);
        if (focus) ImGui.setKeyboardFocusHere();
        ImGui.inputText("Input", text);
        ImGui.text("Actual SDL/native surface");
        ImGui.end();
        ImGui.render();
        ImGui.updatePlatformWindows();
        ImGui.renderPlatformWindowsDefault();
        var p = ImGui.getPlatformIO();
        for (int i = 1; i < p.getViewportsSize(); i++) {
            var surface = platform.getSurface(p.getViewports(i));
            if (surface != null) check(surface.isAcquired(), "Native detached surface acquired before actual submission");
        }
        RenderSystem.getDevice().createCommandEncoder().submit();
        for (int i = 1; i < p.getViewportsSize(); i++) platform.swapBuffers(p.getViewports(i));
        SDL_PumpEvents();
    }

    private static ImGuiViewport detached() {
        var p = ImGui.getPlatformIO();
        return p.getViewportsSize() < 2 ? null : new ImGuiViewport(p.getViewports(1).ptr);
    }

    private static void pushKey(int window, boolean down, int key, int scan, short modifiers) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var event = SDL_Event.calloc(stack); event.type(down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP);
            event.key().windowID(window).key(key).scancode(scan).mod(modifiers).down(down);
            check(SDL_PushEvent(event), "SDL accepts key event");
        }
    }

    private static void pushButton(int window, boolean down, int button) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var event = SDL_Event.calloc(stack); event.type(down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP);
            event.button().windowID(window).button((byte) button).down(down);
            check(SDL_PushEvent(event), "SDL accepts mouse event");
        }
    }

    private static final class SurfaceTarget implements ImGuiWindowHandler.RenderViewportData {
        TextureTarget target;
        boolean closed;
        void resize(long window) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var w = stack.mallocInt(1); var h = stack.mallocInt(1); SDL_GetWindowSizeInPixels(window, w, h);
                if (target == null) target = new TextureTarget("SDL surface acceptance", w.get(0), h.get(0), GpuFormat.RGBA8_UNORM, null);
                else if (target.width != w.get(0) || target.height != h.get(0)) target.resize(w.get(0), h.get(0));
            }
        }
        @Override public RenderTarget getRenderTarget() { return target; }
        @Override public void free() { if (target != null) target.destroyBuffers(); target = null; closed = true; }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
