package foundry.imgui.fabric.mixin;

import foundry.imgui.impl.ImGuiMCImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Initialize after the SDL window/device and client services exist, before the first reload. */
@Mixin(Minecraft.class)
public class WindowMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
    private void imguimc$initBeforeInitialReload(CallbackInfo ci) {
        ImGuiMCImpl.initHandler();
        if (ImGuiMCImpl.handler != null) {
            ((ReloadableResourceManager) Minecraft.getInstance().getResourceManager()).registerReloadListener(ImGuiMCImpl.handler.getFontManager());
        }
    }
}
