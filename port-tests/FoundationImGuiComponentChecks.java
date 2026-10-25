import foundry.imgui.api.ImGuiMC;
import imgui.ImGui;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLClipboard;

/** Genuine native component contracts executed without replacing the installed production stack. */
public final class FoundationImGuiComponentChecks {
    public static void run(ClientGameTestContext test,TestSingleplayerContext world,Map<String,Object> report,List<String> checks){
        var result=new LinkedHashMap<String,Object>();report.put("imguiComponents",result);
        result.put("scope","Actual installed ImGuiMC classes and real GPU/SDL inside the complete native world. Renderer/platform acceptance uses temporary native contexts, then returns ownership to the installed host. No production classes are compiled into the driver.");
        var selection=System.getProperty("foundation.components","all");
        if(selection.equals("all")||selection.equals("renderer"))test.runOnClient(client->{
            var data=NativeRendererAcceptance.verifyNativeRenderer();result.put("renderer",data);
            check("passed".equals(data.get("status")),"Installed native ImGui renderer contract failed: "+data);
        });
        if(selection.equals("all")||selection.equals("platform"))test.runOnClient(client->{
            var data=new LinkedHashMap<String,Object>();result.put("platform",data);var clipboard=SDLClipboard.SDL_GetClipboardText();
            try{
                var method=NativeSdlPlatformAcceptance.class.getDeclaredMethod("run",Minecraft.class,Map.class);method.setAccessible(true);method.invoke(null,client,data);
            }catch(java.lang.reflect.InvocationTargetException failure){throw new AssertionError("Installed SDL platform contract failed",failure.getCause());}
            catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            finally{if(clipboard!=null)SDLClipboard.SDL_SetClipboardText(clipboard);}
        });
        int before=test.computeOnClient(client->{try(var context=ImGuiMC.withImGui()){check(context!=null,"Installed host context was lost after temporary native contexts");return ImGui.getFrameCount();}});
        test.waitFor(client->{try(var context=ImGuiMC.withImGui()){return context!=null&&ImGui.getFrameCount()>=before+5;}},300);
        result.put("installedHostResumed",true);result.put("screenshot",test.takeScreenshot("foundation-imgui-native-components-returned-to-world").toString());result.put("status","passed");
        checks.add("Actual native ImGui RGBA8/RGBA16F pixels, alpha/scissor/scaling, deferred retirement and native SDL detached-window/surface/input/resize/focus lifecycle; installed Minecraft host resumes after temporary context destruction");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private FoundationImGuiComponentChecks(){}
}
