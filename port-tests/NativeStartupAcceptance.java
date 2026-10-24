import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.*;
import foundry.imgui.api.*;
import foundry.imgui.impl.ImGuiMCImpl;
import imgui.ImGui;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.system.MemoryStack;
import java.nio.file.*;
import java.util.*;

/** Genuine packaged production mod bootstrap, resource fonts, native host frames and shutdown. */
public final class NativeStartupAcceptance implements ClientModInitializer {
    private final Map<String,Object> report=new LinkedHashMap<>();
    private int pre,post,ticks,probeX,probeY;
    private boolean complete,retired;
    private GpuTexture texture;private GpuTextureView view;private GpuSampler sampler;
    @Override public void onInitializeClient(){
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            if(Boolean.TRUE.equals(report.get("rendered"))&&ImGuiMCImpl.handler==null&&retired){report.put("status","passed");report.put("productionShutdown",true);}
            else{report.put("status","failed");report.putIfAbsent("error","Production initialization/frame/shutdown did not complete");}
            try{Files.writeString(Path.of(System.getProperty("imguimc.startup.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){failure.printStackTrace();}
        },"actual ImGui production acceptance report"));
        ImGuiMCEvents.INSTANCE.preRenderImGuiEvent(()->{
            pre++;if(view==null||view.isClosed())return;
            ImGui.begin("Native production startup");ImGui.text("Native resource font: λЖ");ImGui.end();
            final long id=ImGuiMCImpl.handler.getRenderer().getImGuiId(ImGuiMC.getTexture(view),ImGuiMC.getSampler(sampler));
            ImGui.getForegroundDrawList().addImage(id,10,10,26,26);
            final var target=net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
            probeX=(int)(18*target.width/ImGui.getIO().getDisplaySizeX());probeY=(int)(18*target.height/ImGui.getIO().getDisplaySizeY());
        });
        ImGuiMCEvents.INSTANCE.postRenderImGuiEvent(()->post++);
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(complete||!client.isGameLoadFinished()||++ticks<5)return;
            try{
                check(ImGuiMCImpl.handler!=null,"Packaged production initializer installed native handler");
                if(texture==null){
                    final var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
                    texture=device.createTexture("production host ImGui sampled image",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,1,1,1,1);view=device.createTextureView(texture);
                    sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.of(0));
                    try(var stack=MemoryStack.stackPush()) {final var bytes=stack.malloc(4);bytes.put(new byte[]{0,(byte)255,0,(byte)255}).flip();final var upload=device.createCommandEncoder();upload.writeToTexture(texture,bytes,0,0,0,0,1,1);upload.submit();}
                    report.put("preAtImage",pre);ImGuiMCImpl.handler.updateFonts();return;
                }
                if(pre-((Number)report.get("preAtImage")).intValue()<4||post<4)return;
                try(var context=ImGuiMC.withImGui()){check(context!=null&&ImGuiMC.getFont(ImGuiMC.FONT_DEFAULT,false,false)!=null,"Actual resource font reload installed usable native fonts");}
                final var target=client.gameRenderer.mainRenderTarget().getColorTexture();check(target.getFormat()==GpuFormat.RGBA8_UNORM,"Native host main color uses RGBA8");
                try(var buffer=RenderSystem.getDevice().createBuffer(()->"actual host ImGui pixel",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,4)){
                    final var commands=RenderSystem.getDevice().createCommandEncoder();commands.copyTextureToBuffer(target,buffer,0,()->{},0,probeX,probeY,1,1);
                    try(var fence=commands.createFence()){commands.submit();check(fence.awaitCompletion(10_000_000_000L),"Actual host ImGui readback completes");try(var mapped=buffer.map(true,false)){final var pixels=mapped.data();check((pixels.get(0)&255)==0&&(pixels.get(1)&255)==255&&(pixels.get(2)&255)==0&&(pixels.get(3)&255)==255,"Actual production hook renders the typed image into native main before presentation");}}
                }
                report.put("rendered",true);report.put("preFrames",pre);report.put("postFrames",post);report.put("checks",List.of("genuine packaged mod and included binding/native library discovery","actual Minecraft initialization and initial resource-font reload","real host pre/post frame hooks render typed image into main before native presentation","font rebuild after actual startup remains usable","actual Minecraft shutdown drains resources and native handler ownership"));
                complete=true;ImGuiMC.afterDraw(()->{view.close();texture.close();sampler.close();retired=true;});client.stop();
            }catch(Throwable failure){failure.printStackTrace();report.put("error",failure.toString());complete=true;client.stop();}
        });
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
