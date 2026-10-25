import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.*;
import foundry.imgui.impl.renderer.v1.ImGuiRenderImplRenderSystem;
import imgui.ImGui;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Actual native ImGui draw lists/pipeline, typed images and post-submit resource retirement. */
public final class NativeRendererAcceptance implements ClientModInitializer {
    private int ticks;private boolean ran;
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(ran||++ticks<5||!client.isGameLoadFinished())return;ran=true;
        final var report=verifyNativeRenderer();
        try{Files.writeString(Path.of(System.getProperty("imguimc.renderer.report")),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception failure){throw new RuntimeException(failure);}client.stop();
    });}
    /** The same genuine GPU assertions, callable inside a complete installed-mod world. */
    public static Map<String,Object> verifyNativeRenderer(){
        final var report=new LinkedHashMap<String,Object>();final var device=RenderSystem.getDevice();report.put("backend",device.getDeviceInfo().backendName());report.put("device",device.getDeviceInfo().name());
        final var previous=ImGui.getCurrentContext();final var context=ImGui.createContext();final var renderer=new ImGuiRenderImplRenderSystem();boolean initialized=false;
        final var target=new TextureTarget("actual native ImGui RGBA8",16,16,GpuFormat.RGBA8_UNORM,null);
        final var hdr=new TextureTarget("actual native ImGui RGBA16F",8,8,GpuFormat.RGBA16_FLOAT,null);
        final var source=device.createTexture("native ImGui opaque source",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,1,1,1,1);
        final var alpha=device.createTexture("native ImGui alpha source",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA8_UNORM,1,1,1,1);
        final var view=device.createTextureView(source);final var alphaView=device.createTextureView(alpha);
        try(var sampler=device.createSampler(AddressMode.CLAMP_TO_EDGE,AddressMode.CLAMP_TO_EDGE,FilterMode.NEAREST,FilterMode.NEAREST,1,OptionalDouble.of(0));var stack=MemoryStack.stackPush()){
            final var io=ImGui.getIO();io.setIniFilename(null);io.setDisplaySize(8,8);io.setDisplayFramebufferScale(1,1);io.setDeltaTime(1f/60);
            final var commands=device.createCommandEncoder();final var opaque=stack.malloc(4);opaque.put(new byte[]{(byte)255,0,0,(byte)255}).flip();commands.writeToTexture(source,opaque,0,0,0,0,1,1);
            final var translucent=stack.malloc(4);translucent.put(new byte[]{(byte)255,0,0,(byte)128}).flip();commands.writeToTexture(alpha,translucent,0,0,0,0,1,1);
            commands.clearColorTexture(target.getColorTexture(),new Vector4f(0,0,1,1));commands.clearColorTexture(hdr.getColorTexture(),new Vector4f(0,0,1,1));commands.submit();
            renderer.init();initialized=true;renderer.newFrame();ImGui.newFrame();
            final long opaqueId=renderer.addTexture(view,sampler),alphaId=renderer.addTexture(alphaView,sampler);final var draw=ImGui.getForegroundDrawList();
            draw.pushClipRect(-2,-2,4,4,false);draw.addImage(opaqueId,0,0,8,8);draw.popClipRect();
            draw.pushClipRect(10,10,12,12,false);draw.addImage(opaqueId,0,0,8,8);draw.popClipRect();
            draw.addRectFilled(4,4,8,8,0xff00ff00);draw.addImage(alphaId,0,4,4,8);ImGui.render();
            final boolean[] retired={false};renderer.afterDraw(()->{check(!source.isClosed()&&!view.isClosed(),"Queued image stays live until actual submission");view.close();alphaView.close();source.close();alpha.close();retired[0]=true;});
            check(!retired[0],"Deferred native UI release waits for renderer completion");
            renderer.renderDrawData(ImGui.getDrawData(),target);renderer.renderDrawData(ImGui.getDrawData(),hdr);
            renderer.postDraw();check(retired[0]&&source.isClosed()&&view.isClosed(),"Post-draw drains retirement after genuine GPU submissions");
            final var pixels=read(target.getColorTexture());
            int redPixels=0;for(int offset=0;offset<pixels.remaining();offset+=4)if((pixels.get(offset)&255)==255&&pixels.get(offset+1)==0&&pixels.get(offset+2)==0)redPixels++;
            report.put("opaqueRedPixelCount",redPixels);
            check(redPixels==64,"Partly offscreen clip must leave exactly 64 opaque red pixels, observed "+redPixels);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){
                final int[] expected=y<8?(x<8?new int[]{255,0,0,255}:new int[]{0,0,255,255}):(x<8?new int[]{128,0,127,255}:new int[]{0,255,0,255});
                for(int channel=0;channel<4;channel++)check(Math.abs((pixels.get((y*16+x)*4+channel)&255)-expected[channel])<=1,"Actual ImGui clipped/scaled/alpha/font pixel mismatch at "+x+","+y+" channel "+channel+": "+(pixels.get((y*16+x)*4+channel)&255));
            }
            final var hdrPixels=read(hdr.getColorTexture()).order(ByteOrder.nativeOrder());
            check((hdrPixels.getShort(0)&65535)==0x3c00&&hdrPixels.getShort(2)==0&&hdrPixels.getShort(4)==0&&(hdrPixels.getShort(6)&65535)==0x3c00,"Actual target-format variant renders genuine half-float output");
            check(!target.getColorTexture().isClosed()&&!hdr.getColorTexture().isClosed(),"Renderer never disposes caller render targets");
            report.put("status","passed");report.put("checks",List.of("actual native ImGui draw data renders exact presented RGBA pixels; font atlas initializes","typed native sampler/image bindings and alpha blend","partly offscreen and fully outside scissor bounds, framebuffer/logical ratios and Minecraft surface orientation","actual RGBA8 and RGBA16F target-format compiled variants","queued preview resources retire only after real encoder submission; caller targets survive"));
        }catch(Throwable failure){failure.printStackTrace();report.put("status","failed");report.put("error",failure.toString());}
        finally{
            if(initialized)renderer.free();view.close();alphaView.close();source.close();alpha.close();target.destroyBuffers();hdr.destroyBuffers();ImGui.destroyContext(context);ImGui.setCurrentContext(previous);
        }
        return report;
    }
    private static ByteBuffer read(GpuTexture texture){
        final var device=RenderSystem.getDevice();final int size=texture.getWidth(0)*texture.getHeight(0)*texture.getFormat().blockSize();
        try(var buffer=device.createBuffer(()->"native ImGui exact pixels",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,size)){
            final var commands=device.createCommandEncoder();commands.copyTextureToBuffer(texture,buffer,0,()->{},0);
            try(var fence=commands.createFence()){commands.submit();check(fence.awaitCompletion(10_000_000_000L),"Native ImGui pixel readback completed");try(var mapped=buffer.map(true,false)){
                // Compare in the same screen orientation as native surface presentation and vanilla Screenshot.
                final var bytes=ByteBuffer.allocate(size);int row=texture.getWidth(0)*texture.getFormat().blockSize();
                for(int y=texture.getHeight(0)-1;y>=0;y--)bytes.put(mapped.data().slice(y*row,row));bytes.flip();return bytes;
            }}
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
