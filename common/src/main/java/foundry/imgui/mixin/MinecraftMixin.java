package foundry.imgui.mixin;

import foundry.imgui.impl.ImGuiMCImpl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=extract"))
    private void imguimc$beginFrame(CallbackInfo ci) {
        if (ImGuiMCImpl.handler != null) ImGuiMCImpl.handler.beginFrame();
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
    private void imguimc$drawBeforeMainSurfaceBlit(CallbackInfo ci) {
        if (ImGuiMCImpl.handler != null) ImGuiMCImpl.handler.endFrame();
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V", shift = At.Shift.AFTER))
    private void imguimc$presentSubmittedSurfaces(CallbackInfo ci) {
        if (ImGuiMCImpl.handler != null) ImGuiMCImpl.handler.swapBuffers();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void imguimc$shutdown(CallbackInfo ci) {
        if (ImGuiMCImpl.handler != null) {
            try { ImGuiMCImpl.handler.shutdown(); }
            finally { ImGuiMCImpl.handler = null; }
        }
    }
}
