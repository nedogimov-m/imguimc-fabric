package foundry.imgui.impl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import foundry.imgui.mixin.RenderSystemBackendAccessor;
import foundry.imgui.mixin.TextInputManagerAccessor;
import imgui.*;
import imgui.callback.*;
import imgui.flag.*;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

import static org.lwjgl.sdl.SDLClipboard.*;
import static org.lwjgl.sdl.SDLError.*;
import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.sdl.SDLGamepad.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLKeyboard.*;
import static org.lwjgl.sdl.SDLKeycode.*;
import static org.lwjgl.sdl.SDLMouse.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLScancode.*;
import static org.lwjgl.sdl.SDLStdinc.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** SDL3 platform owner. SDL watches copy values only; ImGui is accessed on the render thread. */
public final class ImGuiWindowHandlerSDL3 implements ImGuiWindowHandler {
    private final LongSupplier frame;
    private final BooleanSupplier hostTextInput;
    private GpuBackend backend;
    private long mainWindow;
    private final Map<Integer, ViewportData> windows = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Integer> keyOwners = new HashMap<>();
    private final Map<Integer, Integer> mouseOwners = new HashMap<>();
    private final long[] cursors = new long[ImGuiMouseCursor.COUNT];
    private SDL_EventFilter watch;
    private long previousCursor;
    private boolean previousCursorVisible;
    private long gamepad;
    private boolean gamepadSubsystem;
    private boolean viewports;
    private boolean globalMouse;
    private boolean reportHoveredViewport;
    private boolean initialized;
    private boolean mainTextInputOwned;
    private long time;
    private int framebufferWidth;
    private int framebufferHeight;

    public ImGuiWindowHandlerSDL3(ImGuiHandler handler) {
        this(null, handler::getFrame, () -> ((TextInputManagerAccessor) Minecraft.getInstance().textInputManager()).imguimc$isTextInputEnabled());
    }

    /** Also used by native acceptance clients which own their actual backend and SDL window. */
    public ImGuiWindowHandlerSDL3(@Nullable GpuBackend backend, LongSupplier frame) {
        this(backend, frame, () -> false);
    }

    private ImGuiWindowHandlerSDL3(@Nullable GpuBackend backend, LongSupplier frame, BooleanSupplier hostTextInput) {
        this.backend = backend;
        this.frame = frame;
        this.hostTextInput = hostTextInput;
    }

    @Override
    public boolean init(Window window, boolean installCallbacks, ClientApi clientApi) {
        if (backend == null) backend = RenderSystemBackendAccessor.imguimc$getBackend();
        return init(window.handle(), installCallbacks);
    }

    public boolean init(long window, boolean installCallbacks) {
        if (initialized || window == 0 || backend == null) throw new IllegalStateException("SDL platform requires one live window and backend");
        initialized = true;
        mainWindow = window;
        ImGuiIO io = ImGui.getIO();
        io.setBackendPlatformName("imguimc_sdl3");
        io.addBackendFlags(ImGuiBackendFlags.HasMouseCursors | ImGuiBackendFlags.HasSetMousePos);
        // SDL only promises global mouse/capture and desktop positions on these desktop drivers.
        String driver = SDL_GetCurrentVideoDriver();
        globalMouse = java.util.Set.of("windows", "cocoa", "x11", "DIVE", "VMAN").contains(driver);
        reportHoveredViewport = globalMouse && !"cocoa".equals(driver);
        if (globalMouse) io.addBackendFlags(ImGuiBackendFlags.PlatformHasViewports);
        io.setGetClipboardTextFn(new ImStrSupplier() {
            @Override public String get() { String text = SDL_GetClipboardText(); return text == null ? "" : text; }
        });
        io.setSetClipboardTextFn(new ImStrConsumer() {
            @Override public void accept(String text) { SDL_SetClipboardText(text); }
        });
        previousCursor = SDL_GetCursor();
        previousCursorVisible = SDL_CursorVisible();
        int[] shapes = {SDL_SYSTEM_CURSOR_DEFAULT, SDL_SYSTEM_CURSOR_TEXT, SDL_SYSTEM_CURSOR_MOVE,
                SDL_SYSTEM_CURSOR_NS_RESIZE, SDL_SYSTEM_CURSOR_EW_RESIZE, SDL_SYSTEM_CURSOR_NESW_RESIZE,
                SDL_SYSTEM_CURSOR_NWSE_RESIZE, SDL_SYSTEM_CURSOR_POINTER, SDL_SYSTEM_CURSOR_NOT_ALLOWED,
                SDL_SYSTEM_CURSOR_WAIT, SDL_SYSTEM_CURSOR_PROGRESS};
        for (int i = 0; i < Math.min(cursors.length, shapes.length); i++) cursors[i] = SDL_CreateSystemCursor(shapes[i]);
        register(ImGui.getMainViewport(), window, false);
        installPlatformInterface();
        gamepadSubsystem = SDL_InitSubSystem(SDL_INIT_GAMEPAD);
        if (installCallbacks) {
            watch = SDL_EventFilter.create((userdata, address) -> { copyEvent(SDL_Event.create(address)); return true; });
            if (!SDL_AddEventWatch(watch, 0)) throw new IllegalStateException("SDL event watch: " + SDL_GetError());
        }
        updateMonitors();
        return true;
    }

    private record Event(int type, int window, int a, int b, int c, float x, float y, String text) { }

    private void copyEvent(SDL_Event event) {
        int type = event.type();
        Event copy;
        switch (type) {
            case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                SDL_KeyboardEvent e = event.key();
                if (e.repeat()) return;
                copy = new Event(type, e.windowID(), e.key(), e.scancode(), e.mod(), 0, 0, null);
            }
            case SDL_EVENT_TEXT_INPUT -> copy = new Event(type, event.text().windowID(), 0, 0, 0, 0, 0, event.text().textString());
            case SDL_EVENT_MOUSE_MOTION -> copy = new Event(type, event.motion().windowID(), 0, 0, 0, event.motion().x(), event.motion().y(), null);
            case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> copy = new Event(type, event.button().windowID(), event.button().button(), 0, 0, 0, 0, null);
            case SDL_EVENT_MOUSE_WHEEL -> copy = new Event(type, event.wheel().windowID(), event.wheel().direction(), 0, 0, event.wheel().x(), event.wheel().y(), null);
            default -> {
                if (type < SDL_EVENT_WINDOW_FIRST || type > SDL_EVENT_WINDOW_LAST) return;
                copy = new Event(type, event.window().windowID(), event.window().data1(), event.window().data2(), 0, 0, 0, null);
            }
        }
        if (windows.containsKey(copy.window)) events.add(copy);
    }

    /** Drain copied events under this platform owner's current ImGui context. */
    public void processEvents() {
        ImGuiIO io = ImGui.getIO();
        Event e;
        while ((e = events.poll()) != null) {
            ViewportData vd = windows.get(e.window);
            if (vd == null) continue;
            ImGuiViewport vp = vd.viewport;
            switch (e.type) {
                case SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                    modifiers(io, e.c);
                    int key = keyToImGuiKey(e.a, e.b);
                    if (key != ImGuiKey.None) {
                        boolean down = e.type == SDL_EVENT_KEY_DOWN;
                        io.addKeyEvent(key, down);
                        io.setKeyEventNativeData(key, e.a, e.b);
                        if (down) keyOwners.put(key, e.window); else keyOwners.remove(key);
                    }
                }
                case SDL_EVENT_TEXT_INPUT -> { if (e.text != null) io.addInputCharactersUTF8(e.text); }
                case SDL_EVENT_MOUSE_MOTION -> {
                    if (!SDL_GetWindowRelativeMouseMode(vd.window)) {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1);
                            if (viewports) SDL_GetWindowPosition(vd.window, x, y); else { x.put(0, 0); y.put(0, 0); }
                            io.addMousePosEvent(e.x + x.get(0), e.y + y.get(0));
                        }
                    }
                }
                case SDL_EVENT_MOUSE_BUTTON_DOWN, SDL_EVENT_MOUSE_BUTTON_UP -> {
                    modifiers(io, SDL_GetModState());
                    int button = switch (e.a) { case SDL_BUTTON_LEFT -> 0; case SDL_BUTTON_RIGHT -> 1; case SDL_BUTTON_MIDDLE -> 2; case SDL_BUTTON_X1 -> 3; case SDL_BUTTON_X2 -> 4; default -> -1; };
                    if (button >= 0) {
                        boolean down = e.type == SDL_EVENT_MOUSE_BUTTON_DOWN;
                        io.addMouseButtonEvent(button, down);
                        if (down) mouseOwners.put(button, e.window); else mouseOwners.remove(button);
                    }
                }
                case SDL_EVENT_MOUSE_WHEEL -> {
                    float sign = e.a == SDL_MOUSEWHEEL_FLIPPED ? -1 : 1;
                    io.addMouseWheelEvent(-e.x * sign, e.y * sign);
                }
                case SDL_EVENT_WINDOW_FOCUS_GAINED -> io.addFocusEvent(true);
                case SDL_EVENT_WINDOW_FOCUS_LOST -> {
                    long focused = SDL_GetKeyboardFocus();
                    if (focused == 0 || !windows.containsKey(SDL_GetWindowID(focused))) {
                        io.addFocusEvent(false);
                        keyOwners.clear(); mouseOwners.clear(); SDL_CaptureMouse(false);
                    }
                }
                case SDL_EVENT_WINDOW_MOUSE_LEAVE -> { if (mouseOwners.isEmpty()) io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE); }
                case SDL_EVENT_WINDOW_CLOSE_REQUESTED -> { if (vd.owned) vp.setPlatformRequestClose(true); }
                case SDL_EVENT_WINDOW_MOVED -> { if (frame.getAsLong() > vd.ignoreMove + 1) vp.setPlatformRequestMove(true); }
                case SDL_EVENT_WINDOW_RESIZED -> { if (frame.getAsLong() > vd.ignoreResize + 1) vp.setPlatformRequestResize(true); vd.reconfigure = true; }
                case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED -> vd.reconfigure = true;
                default -> { }
            }
        }
    }

    private static void modifiers(ImGuiIO io, int mods) {
        io.addKeyEvent(ImGuiKey.ImGuiMod_Ctrl, (mods & SDL_KMOD_CTRL) != 0);
        io.addKeyEvent(ImGuiKey.ImGuiMod_Shift, (mods & SDL_KMOD_SHIFT) != 0);
        io.addKeyEvent(ImGuiKey.ImGuiMod_Alt, (mods & SDL_KMOD_ALT) != 0);
        io.addKeyEvent(ImGuiKey.ImGuiMod_Super, (mods & SDL_KMOD_GUI) != 0);
    }

    public static int keyToImGuiKey(int keycode, int scancode) {
        // Keypad identity must survive NumLock; text and shortcuts use the current keyboard layout.
        int keypad = switch (scancode) {
            case SDL_SCANCODE_KP_0 -> ImGuiKey.Keypad0; case SDL_SCANCODE_KP_1 -> ImGuiKey.Keypad1;
            case SDL_SCANCODE_KP_2 -> ImGuiKey.Keypad2; case SDL_SCANCODE_KP_3 -> ImGuiKey.Keypad3;
            case SDL_SCANCODE_KP_4 -> ImGuiKey.Keypad4; case SDL_SCANCODE_KP_5 -> ImGuiKey.Keypad5;
            case SDL_SCANCODE_KP_6 -> ImGuiKey.Keypad6; case SDL_SCANCODE_KP_7 -> ImGuiKey.Keypad7;
            case SDL_SCANCODE_KP_8 -> ImGuiKey.Keypad8; case SDL_SCANCODE_KP_9 -> ImGuiKey.Keypad9;
            case SDL_SCANCODE_KP_PERIOD -> ImGuiKey.KeypadDecimal; case SDL_SCANCODE_KP_DIVIDE -> ImGuiKey.KeypadDivide;
            case SDL_SCANCODE_KP_MULTIPLY -> ImGuiKey.KeypadMultiply; case SDL_SCANCODE_KP_MINUS -> ImGuiKey.KeypadSubtract;
            case SDL_SCANCODE_KP_PLUS -> ImGuiKey.KeypadAdd; case SDL_SCANCODE_KP_ENTER -> ImGuiKey.KeypadEnter;
            case SDL_SCANCODE_KP_EQUALS -> ImGuiKey.KeypadEqual; default -> ImGuiKey.None;
        };
        if (keypad != ImGuiKey.None) return keypad;
        if (keycode >= 'a' && keycode <= 'z') return ImGuiKey.A + keycode - 'a';
        if (keycode >= 'A' && keycode <= 'Z') return ImGuiKey.A + keycode - 'A';
        if (keycode >= '0' && keycode <= '9') return ImGuiKey._0 + keycode - '0';
        return switch (scancode) {
            case SDL_SCANCODE_TAB -> ImGuiKey.Tab; case SDL_SCANCODE_LEFT -> ImGuiKey.LeftArrow;
            case SDL_SCANCODE_RIGHT -> ImGuiKey.RightArrow; case SDL_SCANCODE_UP -> ImGuiKey.UpArrow;
            case SDL_SCANCODE_DOWN -> ImGuiKey.DownArrow; case SDL_SCANCODE_PAGEUP -> ImGuiKey.PageUp;
            case SDL_SCANCODE_PAGEDOWN -> ImGuiKey.PageDown; case SDL_SCANCODE_HOME -> ImGuiKey.Home;
            case SDL_SCANCODE_END -> ImGuiKey.End; case SDL_SCANCODE_INSERT -> ImGuiKey.Insert;
            case SDL_SCANCODE_DELETE -> ImGuiKey.Delete; case SDL_SCANCODE_BACKSPACE -> ImGuiKey.Backspace;
            case SDL_SCANCODE_SPACE -> ImGuiKey.Space; case SDL_SCANCODE_RETURN -> ImGuiKey.Enter;
            case SDL_SCANCODE_ESCAPE -> ImGuiKey.Escape; case SDL_SCANCODE_APOSTROPHE -> ImGuiKey.Apostrophe;
            case SDL_SCANCODE_COMMA -> ImGuiKey.Comma; case SDL_SCANCODE_MINUS -> ImGuiKey.Minus;
            case SDL_SCANCODE_PERIOD -> ImGuiKey.Period; case SDL_SCANCODE_SLASH -> ImGuiKey.Slash;
            case SDL_SCANCODE_SEMICOLON -> ImGuiKey.Semicolon; case SDL_SCANCODE_EQUALS -> ImGuiKey.Equal;
            case SDL_SCANCODE_LEFTBRACKET -> ImGuiKey.LeftBracket; case SDL_SCANCODE_BACKSLASH, SDL_SCANCODE_NONUSBACKSLASH -> ImGuiKey.Backslash;
            case SDL_SCANCODE_RIGHTBRACKET -> ImGuiKey.RightBracket; case SDL_SCANCODE_GRAVE -> ImGuiKey.GraveAccent;
            case SDL_SCANCODE_CAPSLOCK -> ImGuiKey.CapsLock; case SDL_SCANCODE_SCROLLLOCK -> ImGuiKey.ScrollLock;
            case SDL_SCANCODE_NUMLOCKCLEAR -> ImGuiKey.NumLock; case SDL_SCANCODE_PRINTSCREEN -> ImGuiKey.PrintScreen;
            case SDL_SCANCODE_PAUSE -> ImGuiKey.Pause; case SDL_SCANCODE_LCTRL -> ImGuiKey.LeftCtrl;
            case SDL_SCANCODE_LSHIFT -> ImGuiKey.LeftShift; case SDL_SCANCODE_LALT -> ImGuiKey.LeftAlt;
            case SDL_SCANCODE_LGUI -> ImGuiKey.LeftSuper; case SDL_SCANCODE_RCTRL -> ImGuiKey.RightCtrl;
            case SDL_SCANCODE_RSHIFT -> ImGuiKey.RightShift; case SDL_SCANCODE_RALT -> ImGuiKey.RightAlt;
            case SDL_SCANCODE_RGUI -> ImGuiKey.RightSuper; case SDL_SCANCODE_APPLICATION -> ImGuiKey.Menu;
            default -> scancode >= SDL_SCANCODE_F1 && scancode <= SDL_SCANCODE_F12 ? ImGuiKey.F1 + scancode - SDL_SCANCODE_F1
                    : scancode >= SDL_SCANCODE_F13 && scancode <= SDL_SCANCODE_F24 ? ImGuiKey.F13 + scancode - SDL_SCANCODE_F13 : ImGuiKey.None;
        };
    }

    @Override
    public void newFrame(RenderTarget target) {
        processEvents();
        ImGuiIO io = ImGui.getIO();
        boolean requested = io.hasBackendFlags(ImGuiBackendFlags.PlatformHasViewports) && io.hasConfigFlags(ImGuiConfigFlags.ViewportsEnable);
        if (viewports && !requested) ImGui.destroyPlatformWindows();
        viewports = requested;
        // DestroyPlatformWindows also clears the borrowed main viewport platform data.
        if (ImGui.getMainViewport().getPlatformUserData() == null) register(ImGui.getMainViewport(), mainWindow, false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1);
            SDL_GetWindowSize(mainWindow, width, height);
            var color = target.getColorTextureView();
            framebufferWidth = color.getWidth(0); framebufferHeight = color.getHeight(0);
            boolean minimized = (SDL_GetWindowFlags(mainWindow) & SDL_WINDOW_MINIMIZED) != 0;
            io.setDisplaySize(minimized ? 0 : width.get(0), minimized ? 0 : height.get(0));
            if (width.get(0) > 0 && height.get(0) > 0) io.setDisplayFramebufferScale((float) framebufferWidth / width.get(0), (float) framebufferHeight / height.get(0));
        }
        long now = System.nanoTime();
        io.setDeltaTime(time == 0 ? 1.0f / 60 : Math.max((now - time) / 1.0e9f, 0.00001f));
        time = now;
        updateMonitors();
        // SDL reports the capturing window while dragging. Let ImGui infer the viewport underneath it.
        if (reportHoveredViewport && mouseOwners.isEmpty()) io.addBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
        else io.removeBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
        updateMouse(io);
        updateGamepad(io);
        long focused = SDL_GetKeyboardFocus();
        // Preserve Minecraft's independent text owner when ImGui relinquishes the shared window.
        boolean mainInput = focused == mainWindow && io.getWantTextInput();
        if (mainInput) { if (!SDL_TextInputActive(mainWindow)) SDL_StartTextInput(mainWindow); mainTextInputOwned = true; }
        else if (mainTextInputOwned) { if (!hostTextInput.getAsBoolean()) SDL_StopTextInput(mainWindow); mainTextInputOwned = false; }
        for (ViewportData vd : windows.values()) if (vd.owned) {
            boolean input = focused == vd.window && io.getWantTextInput();
            if (input && !SDL_TextInputActive(vd.window)) SDL_StartTextInput(vd.window);
            if (!input && SDL_TextInputActive(vd.window)) SDL_StopTextInput(vd.window);
        }
    }

    private void updateMouse(ImGuiIO io) {
        long focused = SDL_GetKeyboardFocus();
        boolean ours = windows.containsKey(focused == 0 ? 0 : SDL_GetWindowID(focused));
        if (ours && !SDL_GetWindowRelativeMouseMode(focused)) {
            if (globalMouse) SDL_CaptureMouse(!mouseOwners.isEmpty());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var x = stack.mallocFloat(1); var y = stack.mallocFloat(1);
                if (io.getWantSetMousePos()) {
                    if (viewports) SDL_WarpMouseGlobal(io.getMousePosX(), io.getMousePosY());
                    else SDL_WarpMouseInWindow(mainWindow, io.getMousePosX(), io.getMousePosY());
                } else if (viewports) {
                    SDL_GetGlobalMouseState(x, y); io.addMousePosEvent(x.get(0), y.get(0));
                } else if (SDL_GetMouseFocus() == mainWindow) {
                    SDL_GetMouseState(x, y); io.addMousePosEvent(x.get(0), y.get(0));
                }
            }
            if (!io.hasConfigFlags(ImGuiConfigFlags.NoMouseCursorChange)) {
                int cursor = ImGui.getMouseCursor();
                if (io.getMouseDrawCursor() || cursor == ImGuiMouseCursor.None) SDL_HideCursor();
                else { SDL_SetCursor(cursor >= 0 && cursor < cursors.length && cursors[cursor] != 0 ? cursors[cursor] : SDL_GetDefaultCursor()); SDL_ShowCursor(); }
            }
        }
        if (io.hasBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport)) {
            long hover = SDL_GetMouseFocus();
            ViewportData vd = hover == 0 ? null : windows.get(SDL_GetWindowID(hover));
            io.addMouseViewportEvent(vd != null && !vd.viewport.hasFlags(ImGuiViewportFlags.NoInputs) ? vd.viewport.getID() : 0);
        }
    }

    private static final int[][] GAMEPAD_BUTTONS = {
            {ImGuiKey.GamepadStart, SDL_GAMEPAD_BUTTON_START}, {ImGuiKey.GamepadBack, SDL_GAMEPAD_BUTTON_BACK},
            {ImGuiKey.GamepadFaceLeft, SDL_GAMEPAD_BUTTON_WEST}, {ImGuiKey.GamepadFaceRight, SDL_GAMEPAD_BUTTON_EAST},
            {ImGuiKey.GamepadFaceUp, SDL_GAMEPAD_BUTTON_NORTH}, {ImGuiKey.GamepadFaceDown, SDL_GAMEPAD_BUTTON_SOUTH},
            {ImGuiKey.GamepadDpadLeft, SDL_GAMEPAD_BUTTON_DPAD_LEFT}, {ImGuiKey.GamepadDpadRight, SDL_GAMEPAD_BUTTON_DPAD_RIGHT},
            {ImGuiKey.GamepadDpadUp, SDL_GAMEPAD_BUTTON_DPAD_UP}, {ImGuiKey.GamepadDpadDown, SDL_GAMEPAD_BUTTON_DPAD_DOWN},
            {ImGuiKey.GamepadL1, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER}, {ImGuiKey.GamepadR1, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER},
            {ImGuiKey.GamepadL3, SDL_GAMEPAD_BUTTON_LEFT_STICK}, {ImGuiKey.GamepadR3, SDL_GAMEPAD_BUTTON_RIGHT_STICK}};

    private void updateGamepad(ImGuiIO io) {
        io.removeBackendFlags(ImGuiBackendFlags.HasGamepad);
        if (!gamepadSubsystem) return;
        if (gamepad != 0 && !SDL_GamepadConnected(gamepad)) {
            for (int key = ImGuiKey.GamepadStart; key <= ImGuiKey.GamepadRStickDown; key++) io.addKeyAnalogEvent(key, false, 0);
            SDL_CloseGamepad(gamepad); gamepad = 0;
        }
        if (!io.hasConfigFlags(ImGuiConfigFlags.NavEnableGamepad)) return;
        if (gamepad == 0) {
            IntBuffer ids = SDL_GetGamepads();
            if (ids != null) { try { for (int i = 0; i < ids.remaining() && gamepad == 0; i++) gamepad = SDL_OpenGamepad(ids.get(i)); } finally { SDL_free(ids); } }
        }
        if (gamepad == 0) return;
        SDL_UpdateGamepads();
        io.addBackendFlags(ImGuiBackendFlags.HasGamepad);
        for (int[] entry : GAMEPAD_BUTTONS) io.addKeyEvent(entry[0], SDL_GetGamepadButton(gamepad, entry[1]));
        analog(io, ImGuiKey.GamepadL2, SDL_GAMEPAD_AXIS_LEFT_TRIGGER, 0, 32767);
        analog(io, ImGuiKey.GamepadR2, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, 0, 32767);
        stick(io, SDL_GAMEPAD_AXIS_LEFTX, ImGuiKey.GamepadLStickLeft, ImGuiKey.GamepadLStickRight);
        stick(io, SDL_GAMEPAD_AXIS_LEFTY, ImGuiKey.GamepadLStickUp, ImGuiKey.GamepadLStickDown);
        stick(io, SDL_GAMEPAD_AXIS_RIGHTX, ImGuiKey.GamepadRStickLeft, ImGuiKey.GamepadRStickRight);
        stick(io, SDL_GAMEPAD_AXIS_RIGHTY, ImGuiKey.GamepadRStickUp, ImGuiKey.GamepadRStickDown);
    }

    private void stick(ImGuiIO io, int axis, int negative, int positive) { analog(io, negative, axis, -8000, -32768); analog(io, positive, axis, 8000, 32767); }
    private void analog(ImGuiIO io, int key, int axis, float zero, float full) { float value = Math.clamp((SDL_GetGamepadAxis(gamepad, axis) - zero) / (full - zero), 0, 1); io.addKeyAnalogEvent(key, value > 0.1f, value); }

    private void updateMonitors() {
        IntBuffer displays = SDL_GetDisplays();
        if (displays == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect bounds = SDL_Rect.malloc(stack), work = SDL_Rect.malloc(stack);
            boolean first = true;
            for (int i = 0; i < displays.remaining(); i++) {
                int display = displays.get(i);
                if (!SDL_GetDisplayBounds(display, bounds) || bounds.w() <= 0 || bounds.h() <= 0) continue;
                if (!SDL_GetDisplayUsableBounds(display, work) || work.w() <= 0 || work.h() <= 0) work.set(bounds);
                float scale = getContentScaleForMonitor(display);
                if (scale <= 0) continue;
                ImGuiPlatformIO platform = ImGui.getPlatformIO();
                if (first) { platform.resizeMonitors(0); first = false; }
                platform.pushMonitors(Integer.toUnsignedLong(display), bounds.x(), bounds.y(), bounds.w(), bounds.h(), work.x(), work.y(), work.w(), work.h(), scale);
            }
        } finally { SDL_free(displays); }
    }

    @Override public float getContentScaleForMonitor(long display) { return SDL_GetDisplayContentScale((int) display); }
    @Override public boolean isCorrectSize(int width, int height) { return framebufferWidth == width && framebufferHeight == height; }

    private static final class ViewportData {
        final ImGuiViewport viewport;
        final long window;
        final boolean owned;
        GpuSurface surface;
        RenderViewportData renderer;
        boolean reconfigure = true;
        long ignoreMove = -2, ignoreResize = -2;
        ViewportData(ImGuiViewport viewport, long window, boolean owned) { this.viewport = new ImGuiViewport(viewport.ptr); this.window = window; this.owned = owned; }
    }

    private ViewportData register(ImGuiViewport vp, long window, boolean owned) {
        ViewportData vd = new ViewportData(vp, window, owned);
        windows.put(SDL_GetWindowID(window), vd);
        vp.setPlatformUserData(vd); vp.setPlatformHandle(window);
        int properties = SDL_GetWindowProperties(window);
        long raw = SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
        if (raw == 0) raw = SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0);
        if (raw == 0) raw = SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER, 0);
        if (raw == 0) raw = SDL_GetNumberProperty(properties, SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0);
        vp.setPlatformHandleRaw(raw);
        return vd;
    }

    private static ViewportData data(ImGuiViewport vp) { return (ViewportData) vp.getPlatformUserData(); }

    @SuppressWarnings("unchecked")
    @Override public <T extends RenderViewportData> @Nullable T getRenderData(ImGuiViewport vp, Supplier<T> factory) {
        ViewportData vd = data(vp);
        if (vd == null) return null;
        if (vd.renderer == null) vd.renderer = factory.get();
        return (T) vd.renderer;
    }

    public @Nullable GpuSurface getSurface(ImGuiViewport vp) { ViewportData vd = data(vp); return vd == null ? null : vd.surface; }

    private void createWindow(ImGuiViewport vp) {
        long flags = SDL_WINDOW_HIDDEN | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY;
        if (vp.hasFlags(ImGuiViewportFlags.NoDecoration)) flags |= SDL_WINDOW_BORDERLESS;
        if (vp.hasFlags(ImGuiViewportFlags.TopMost)) flags |= SDL_WINDOW_ALWAYS_ON_TOP;
        if (vp.hasFlags(ImGuiViewportFlags.NoTaskBarIcon)) flags |= SDL_WINDOW_UTILITY;
        if (vp.hasFlags(ImGuiViewportFlags.NoFocusOnAppearing)) flags |= SDL_WINDOW_NOT_FOCUSABLE;
        long window = backend.createWindow("ImGui", Math.max(1, (int) vp.getSizeX()), Math.max(1, (int) vp.getSizeY()), flags);
        if (window == 0) throw new IllegalStateException("SDL viewport creation: " + SDL_GetError());
        ViewportData vd = register(vp, window, true);
        try {
            vd.surface = RenderSystem.getDevice().createSurface(window, () -> (SDL_GetWindowFlags(window) & SDL_WINDOW_MINIMIZED) != 0);
            SDL_SetWindowPosition(window, (int) vp.getPosX(), (int) vp.getPosY());
        } catch (Throwable failure) { destroyWindow(vp); throw failure; }
    }

    private void destroyWindow(ImGuiViewport vp) {
        ViewportData vd = data(vp);
        if (vd == null) return;
        int id = SDL_GetWindowID(vd.window);
        ImGuiIO io = ImGui.getIO();
        keyOwners.entrySet().removeIf(entry -> { if (entry.getValue() != id) return false; io.addKeyEvent(entry.getKey(), false); return true; });
        mouseOwners.entrySet().removeIf(entry -> { if (entry.getValue() != id) return false; io.addMouseButtonEvent(entry.getKey(), false); return true; });
        windows.remove(id);
        // Submitted renderer resources and native surfaces must be released before the SDL window.
        if (vd.renderer != null) { vd.renderer.free(); vd.renderer = null; }
        if (vd.surface != null) { vd.surface.close(); vd.surface = null; }
        if (vd.owned) { SDL_StopTextInput(vd.window); SDL_DestroyWindow(vd.window); }
        vp.setPlatformUserData(null); vp.setPlatformHandle(0); vp.setPlatformHandleRaw(0);
    }

    private void prepareSurface(ImGuiViewport vp) {
        ViewportData vd = data(vp);
        if (vd == null || vd.surface == null || vd.renderer == null || vd.surface.isAcquired()) return;
        if ((SDL_GetWindowFlags(vd.window) & SDL_WINDOW_MINIMIZED) != 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            SDL_GetWindowSizeInPixels(vd.window, w, h);
            if (w.get(0) <= 0 || h.get(0) <= 0) return;
            if (vd.reconfigure || vd.surface.isSuboptimal() || vd.surface.currentConfiguration().isEmpty()) {
                vd.surface.configure(new GpuSurface.Configuration(w.get(0), h.get(0), GpuSurface.PresentMode.getSupportedVsyncMode(vd.surface.supportedPresentModes(), true)));
                vd.reconfigure = false;
            }
            vd.surface.acquireNextTexture();
            var color = vd.renderer.getRenderTarget().getColorTextureView();
            if (color == null) throw new IllegalStateException("ImGui viewport color texture missing");
            vd.surface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), color);
        } catch (SurfaceException failure) {
            vd.reconfigure = true;
            ImGuiMCImpl.LOGGER.warn("ImGui SDL surface requires reconfiguration", failure);
        }
    }

    @Override public void swapBuffers(ImGuiViewport vp) { GpuSurface surface = getSurface(vp); if (surface != null && surface.isAcquired()) surface.present(); }

    private void installPlatformInterface() {
        ImGuiPlatformIO p = ImGui.getPlatformIO();
        p.setPlatformCreateWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport vp) { createWindow(vp); } });
        p.setPlatformDestroyWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport vp) { destroyWindow(vp); } });
        p.setPlatformShowWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport vp) {
            ViewportData vd = data(vp); SDL_ShowWindow(vd.window);
            if (vp.hasFlags(ImGuiViewportFlags.NoFocusOnAppearing)) SDL_SetWindowFocusable(vd.window, true);
        } });
        p.setPlatformGetWindowPos(new ImPlatformFuncViewportSuppImVec2() { @Override public void get(ImGuiViewport vp, ImVec2 dst) {
            try (MemoryStack stack = MemoryStack.stackPush()) { IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1); SDL_GetWindowPosition(data(vp).window, x, y); dst.set(x.get(0), y.get(0)); }
        } });
        p.setPlatformSetWindowPos(new ImPlatformFuncViewportImVec2() { @Override public void accept(ImGuiViewport vp, ImVec2 pos) { ViewportData vd = data(vp); vd.ignoreMove = frame.getAsLong(); SDL_SetWindowPosition(vd.window, (int) pos.x, (int) pos.y); } });
        p.setPlatformGetWindowSize(new ImPlatformFuncViewportSuppImVec2() { @Override public void get(ImGuiViewport vp, ImVec2 dst) {
            try (MemoryStack stack = MemoryStack.stackPush()) { IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1); SDL_GetWindowSize(data(vp).window, w, h); dst.set(w.get(0), h.get(0)); }
        } });
        p.setPlatformSetWindowSize(new ImPlatformFuncViewportImVec2() { @Override public void accept(ImGuiViewport vp, ImVec2 size) { ViewportData vd = data(vp); vd.ignoreResize = frame.getAsLong(); SDL_SetWindowSize(vd.window, Math.max(1, (int) size.x), Math.max(1, (int) size.y)); vd.reconfigure = true; } });
        p.setPlatformSetWindowTitle(new ImPlatformFuncViewportString() { @Override public void accept(ImGuiViewport vp, String title) { SDL_SetWindowTitle(data(vp).window, title); } });
        p.setPlatformSetWindowFocus(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport vp) { SDL_RaiseWindow(data(vp).window); } });
        p.setPlatformGetWindowFocus(new ImPlatformFuncViewportSuppBoolean() { @Override public boolean get(ImGuiViewport vp) { return (SDL_GetWindowFlags(data(vp).window) & SDL_WINDOW_INPUT_FOCUS) != 0; } });
        p.setPlatformGetWindowMinimized(new ImPlatformFuncViewportSuppBoolean() { @Override public boolean get(ImGuiViewport vp) { return (SDL_GetWindowFlags(data(vp).window) & SDL_WINDOW_MINIMIZED) != 0; } });
        p.setPlatformSetWindowAlpha(new ImPlatformFuncViewportFloat() { @Override public void accept(ImGuiViewport vp, float alpha) { SDL_SetWindowOpacity(data(vp).window, alpha); } });
        p.setPlatformGetWindowDpiScale(new ImPlatformFuncViewportSuppFloat() { @Override public float get(ImGuiViewport vp) { return SDL_GetWindowDisplayScale(data(vp).window); } });
        p.setPlatformSwapBuffers(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport vp) { prepareSurface(vp); } });
    }

    @Override public void shutdown() {
        if (!initialized) return;
        if (watch != null) { SDL_RemoveEventWatch(watch, 0); watch.free(); watch = null; }
        ImGui.destroyPlatformWindows();
        // Also covers partially created owners when init fails.
        for (ViewportData vd : windows.values().toArray(ViewportData[]::new)) destroyWindow(vd.viewport);
        events.clear(); keyOwners.clear(); mouseOwners.clear();
        SDL_CaptureMouse(false);
        SDL_SetCursor(previousCursor != 0 ? previousCursor : SDL_GetDefaultCursor());
        if (previousCursorVisible) SDL_ShowCursor(); else SDL_HideCursor();
        for (long cursor : cursors) if (cursor != 0) SDL_DestroyCursor(cursor);
        if (gamepad != 0) { SDL_CloseGamepad(gamepad); gamepad = 0; }
        if (gamepadSubsystem) { SDL_QuitSubSystem(SDL_INIT_GAMEPAD); gamepadSubsystem = false; }
        if (mainTextInputOwned && !hostTextInput.getAsBoolean()) SDL_StopTextInput(mainWindow);
        mainTextInputOwned = false;
        ImGuiIO io = ImGui.getIO();
        io.setBackendPlatformName(null);
        io.removeBackendFlags(ImGuiBackendFlags.HasMouseCursors | ImGuiBackendFlags.HasSetMousePos | ImGuiBackendFlags.HasGamepad | ImGuiBackendFlags.PlatformHasViewports | ImGuiBackendFlags.HasMouseHoveredViewport);
        initialized = false;
    }
}
