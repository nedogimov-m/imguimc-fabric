package foundry.imgui.mixin;

import foundry.imgui.api.ImGuiMC;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    public void keyCallback(final long window, final int action, final KeyEvent event, final CallbackInfo ci) {
        try (final ImGuiMC.ActiveContext ctx = ImGuiMC.withImGui()) {
            if (action != 0 && ctx != null && ctx.io().getWantCaptureKeyboard()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = {"charTyped", "textInput", "textEditing"}, at = @At("HEAD"), cancellable = true)
    public void charCallback(final CallbackInfo ci) {
        try (final ImGuiMC.ActiveContext ctx = ImGuiMC.withImGui()) {
            if (ctx != null && ctx.io().getWantCaptureKeyboard()) {
                ci.cancel();
            }
        }
    }
}
