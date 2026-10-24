package foundry.imgui.mixin;

import com.mojang.blaze3d.platform.TextInputManager;
import com.mojang.blaze3d.platform.Window;
import foundry.imgui.api.ImGuiMC;
import org.lwjgl.sdl.SDLKeyboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextInputManager.class)
public class TextInputManagerMixin {
    @Shadow @Final private Window window;

    @Inject(method = "stopTextInput()V", at = @At("RETURN"))
    private void imguimc$preserveImGuiTextInput(CallbackInfo ci) {
        try (ImGuiMC.ActiveContext context = ImGuiMC.withImGui()) {
            if (context != null && context.io().getWantTextInput()) SDLKeyboard.SDL_StartTextInput(window.handle());
        }
    }
}
