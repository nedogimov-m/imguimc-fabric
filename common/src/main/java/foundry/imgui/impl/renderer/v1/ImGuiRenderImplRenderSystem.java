package foundry.imgui.impl.renderer.v1;



import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import foundry.imgui.api.ImGuiMC;
import foundry.imgui.api.ImGuiSampler;
import foundry.imgui.api.ImGuiTextureProvider;
import foundry.imgui.impl.ImGuiMCImpl;
import foundry.imgui.impl.ImGuiWindowHandler;
import foundry.imgui.impl.renderer.ImGuiRenderer;
import imgui.*;
import imgui.callback.ImPlatformFuncViewport;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiViewportFlags;
import imgui.type.ImInt;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import java.util.*;

@ApiStatus.Internal
public class ImGuiRenderImplRenderSystem implements ImGuiRenderer {

    private static final VertexFormat VERTEX_FORMAT;

    static {
        
        VERTEX_FORMAT = VertexFormat.builder(0)
                .addAttribute("Position", com.mojang.renderpearl.api.GpuFormat.RG32_FLOAT)
                .addAttribute("UV", com.mojang.renderpearl.api.GpuFormat.RG32_FLOAT)
                .addAttribute("Color", com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM)
                .build();
        

        






















    }

    private static final String VERTEX_SHADER = """
            #version 450
            layout (location = 0) in vec2 Position;
            layout (location = 1) in vec2 UV;
            layout (location = 2) in vec4 Color;
            layout(std140) uniform Projection {
                mat4 ProjMtx;
            };
            layout(location=0) out vec2 Frag_UV;
            layout(location=1) out vec4 Frag_Color;
            void main()
            {
                Frag_UV = UV;
                Frag_Color = Color;
                gl_Position = ProjMtx * vec4(Position.xy,0,1);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 450
            layout(location=0) in vec2 Frag_UV;
            layout(location=1) in vec4 Frag_Color;
            uniform sampler2D Texture;
            layout (location = 0) out vec4 Out_Color;
            void main()
            {
                Out_Color = Frag_Color * texture(Texture, Frag_UV.st);
            }
            """;
    private static final Map<Identifier, String> SOURCES_MAP = Map.of(
            Identifier.fromNamespaceAndPath(ImGuiMC.MOD_ID, "shader_vertex"), VERTEX_SHADER,
            Identifier.fromNamespaceAndPath(ImGuiMC.MOD_ID, "shader_fragment"), FRAGMENT_SHADER
    );

    private static RenderPipeline createPipeline(com.mojang.renderpearl.api.GpuFormat format) { return RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(ImGuiMC.MOD_ID, "pipeline/imgui"))
            .withVertexShader(Identifier.fromNamespaceAndPath(ImGuiMC.MOD_ID, "shader_vertex"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(ImGuiMC.MOD_ID, "shader_fragment"))
            
            .withBindGroupLayout(com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder()
                    .withUniform("Texture",UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .build())
            
            


            
            .withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),format,com.mojang.renderpearl.api.pipeline.ColorTargetState.WRITE_ALL))
             
            

            

            .withCull(false)
            
            .withVertexBinding(0, VERTEX_FORMAT)
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            
            

            
            .withDepthStencilState(Optional.empty())
            
            


            .build(); }

    private static String getShaderSource(final Identifier name, final ShaderType shaderType) {
        return SOURCES_MAP.get(name);
    }

    
    public long addTexture(final GpuTextureView view, @Nullable final com.mojang.renderpearl.api.textures.GpuSampler sampler) {
        this.data.textures.add(view);
        this.data.samplers.add(sampler);
        return this.data.textures.size() + 1;
    }
    
    





    /**
     * Data class to store implementation specific fields.
     * Same as {@code ImGui_ImplOpenGL3_Data}.
     */
    protected static class Data {
        protected GpuTextureView fontTextureView;
        protected GpuTexture fontTexture;
        protected ViewportData mainViewportData;
        protected List<GpuTextureView> textures = new ArrayList<>();
        
        protected List<com.mojang.renderpearl.api.textures.GpuSampler> samplers = new ArrayList<>();
        
    }

    /**
     * Data class to store implementation specific fields.
     * Same as {@code ImGui_ImplOpenGL3_Data}.
     */
    protected static class ViewportData implements ImGuiWindowHandler.RenderViewportData {
        protected CachedImguiOrthoBuffer projectionMatrixBuffer;
        protected List<GpuBuffer> vertexData = new ArrayList<>();
        protected List<GpuBuffer> indexData = new ArrayList<>();
        protected int elementSize;
        protected RenderTarget renderTarget;
        protected boolean ownedRenderTarget;

        public void clearVertexData(final int maxCommands) {
            final int removeVertices = this.vertexData.size() - maxCommands;
            if (removeVertices > 0) {
                final Iterator<GpuBuffer> iterator = this.vertexData.iterator();
                for (int i = 0; i < removeVertices; i++) {
                    iterator.next().close();
                    iterator.remove();
                }
            }

            final int removeIndices = this.indexData.size() - maxCommands;
            if (removeIndices > 0) {
                final Iterator<GpuBuffer> iterator = this.indexData.iterator();
                for (int i = 0; i < removeIndices; i++) {
                    iterator.next().close();
                    iterator.remove();
                }
            }
        }

        public void updateRenderTarget(final ImGuiViewport viewport) {
            final int width,height;
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
                final var w=stack.mallocInt(1);final var h=stack.mallocInt(1);
                if(!org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels(viewport.getPlatformHandle(),w,h))throw new IllegalStateException("Cannot query native ImGui viewport framebuffer");
                width=Math.max(1,w.get(0));height=Math.max(1,h.get(0));
            }
            if (this.renderTarget == null) {
                this.renderTarget = ImGuiMCImpl.createRenderTarget(width, height, false);
            } else if (this.renderTarget.width != width || this.renderTarget.height != height) {
                ImGuiMCImpl.resizeRenderTarget(this.renderTarget, width, height);
            }
            this.ownedRenderTarget = true;
        }

        @Override
        public RenderTarget getRenderTarget() {
            return this.renderTarget;
        }

        @Override
        public void free() {
            this.clearVertexData(0);
            if (this.projectionMatrixBuffer != null) {
                this.projectionMatrixBuffer.close();
                this.projectionMatrixBuffer = null;
            }
            if (this.renderTarget != null) {
                if (this.ownedRenderTarget) {
                    this.renderTarget.destroyBuffers();
                }
                this.renderTarget = null;
            }
        }
    }

    /**
     * Internal class to store containers for frequently used arrays.
     * This class helps minimize the number of object allocations on the JVM side,
     * thereby improving performance and reducing garbage collection overhead.
     */
    private static final class Properties {
        private final ImVec4 clipRect = new ImVec4();
    }

    protected Data data = null;
    private final Properties props = new Properties();
    private final List<Runnable> retiredResources=new ArrayList<>();
    @Override public void afterDraw(Runnable action){retiredResources.add(Objects.requireNonNull(action));}
    private void releaseRetired(){final var pending=List.copyOf(retiredResources);retiredResources.clear();RuntimeException failure=null;for(var action:pending){try{action.run();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}}if(failure!=null)throw failure;}
    private final Map<com.mojang.renderpearl.api.GpuFormat,com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline> pipelines=new HashMap<>();
    private com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline pipeline(com.mojang.renderpearl.api.GpuFormat format) {
        return pipelines.computeIfAbsent(format,key->{
            try(var source=new com.mojang.renderpearl.api.pipeline.ShaderSource(){
                public String getShader(Identifier id,ShaderType type){String text=SOURCES_MAP.get(id);if(text==null)throw new IllegalArgumentException("Unknown ImGui shader: "+id);return text;}
                public CachedIncludeSource getInclude(Identifier id){throw new IllegalArgumentException("ImGui inline shader has no include: "+id);}
                public void close(){}
            }) { final var compiled=RenderSystem.getDevice().compilePipeline(createPipeline(key),source,Runnable::run).join().finishCompile();if(compiled==null)throw new IllegalStateException("Failed native ImGui pipeline");return compiled;}
        });
    }

    protected Data newData() {
        return new Data();
    }

    private void clearTextures() {
        
        this.data.samplers.clear();
        

        if (this.data.textures.isEmpty()) {
            return;
        }

        final Iterator<GpuTextureView> iterator = this.data.textures.iterator();
        while (iterator.hasNext()) {
            ImGuiMC.getTexture(iterator.next()).imguimc$setId(0);
            iterator.remove();
        }
    }

    @Override
    public void init() {
        this.data = this.newData();

        final GpuDevice device = RenderSystem.getDevice();

        final ImGuiIO io = ImGui.getIO();
        
        

        io.setBackendRendererName("imgui-java_impl_" + device.getDeviceInfo().backendName());
         

        // We can honor the ImDrawCmd::VtxOffset field, allowing for large meshes.
        io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);

        // We can create multi-viewports on the Renderer side (optional)
        io.addBackendFlags(ImGuiBackendFlags.RendererHasViewports);

        if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            this.initPlatformInterface();
        }
    }

    
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private void renderDrawData(final ImDrawData drawData, final ViewportData data, final Optional<org.joml.Vector4fc> clearColor) {
        
    



        final GpuDevice device = RenderSystem.getDevice();
        final RenderTarget renderTarget = data.renderTarget;

        // Make sure this data is never allocated on another context
        final var compiled=pipeline(renderTarget.getColorTexture().getFormat());

        // Avoid rendering when minimized, scale coordinates for retina displays (screen coordinates != framebuffer coordinates)
        final int fbWidth = renderTarget.width;
        final int fbHeight = renderTarget.height;
        if (fbWidth <= 0 || fbHeight <= 0) {
            data.clearVertexData(0);
            return;
        }

        final int cmdListsCount = drawData.getCmdListsCount();
        if (cmdListsCount <= 0) {
            data.clearVertexData(0);
            return;
        }

        final float L = drawData.getDisplayPosX();
        final float R = drawData.getDisplayPosX() + drawData.getDisplaySizeX();
        final float T = drawData.getDisplayPosY();
        final float B = drawData.getDisplayPosY() + drawData.getDisplaySizeY();

        // Will project scissor/clipping rectangles into framebuffer space
        final float clipOffX = drawData.getDisplayPosX(); // (0,0) unless using multi-viewports
        final float clipOffY = drawData.getDisplayPosY(); // (0,0) unless using multi-viewports
        final float clipScaleX = fbWidth/drawData.getDisplaySizeX(); // (1,1) unless using retina display which are often (2,2)
        final float clipScaleY = fbHeight/drawData.getDisplaySizeY(); // (1,1) unless using retina display which are often (2,2)

        if (ImDrawData.sizeOfImDrawIdx() != data.elementSize) {
            final Iterator<GpuBuffer> iterator = data.indexData.iterator();
            while (iterator.hasNext()) {
                iterator.next().close();
                iterator.remove();
            }
        }

        data.elementSize = ImDrawData.sizeOfImDrawIdx();
        data.clearVertexData(cmdListsCount);

        final CommandEncoder commandEncoder = device.createCommandEncoder();

        // Set up buffers
        for (int n = 0; n < cmdListsCount; n++) {
            final GpuBuffer vertexBuffer;
            final int vertexBufferSize = drawData.getCmdListVtxBufferSize(n) * ImDrawData.sizeOfImDrawVert();

            if (n >= data.vertexData.size()) {
                final int index = n;
                vertexBuffer = device.createBuffer(() -> "ImGui Vertex Buffer " + index, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, vertexBufferSize);
                data.vertexData.add(vertexBuffer);
            } else {
                final GpuBuffer buffer = data.vertexData.get(n);

                if (buffer.size() >= vertexBufferSize) {
                    vertexBuffer = buffer;
                } else {
                    buffer.close();
                    final int index = n;
                    vertexBuffer = device.createBuffer(() -> "ImGui Vertex Buffer " + index, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, vertexBufferSize);
                    data.vertexData.set(n, vertexBuffer);
                }
            }

            final GpuBuffer indexBuffer;
            final int indexBufferSize = drawData.getCmdListIdxBufferSize(n) * data.elementSize;

            if (n >= data.indexData.size()) {
                final int index = n;
                indexBuffer = device.createBuffer(() -> "ImGui Index Buffer " + index, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX, indexBufferSize);
                data.indexData.add(indexBuffer);
            } else {
                final GpuBuffer buffer = data.indexData.get(n);

                if (buffer.size() >= indexBufferSize) {
                    indexBuffer = buffer;
                } else {
                    buffer.close();
                    final int index = n;
                    indexBuffer = device.createBuffer(() -> "ImGui Index Buffer " + index, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX, indexBufferSize);
                    data.indexData.set(n, indexBuffer);
                }
            }

            commandEncoder.writeToBuffer(vertexBuffer.slice(0, vertexBufferSize), drawData.getCmdListVtxBufferData(n));
            commandEncoder.writeToBuffer(indexBuffer.slice(0, indexBufferSize), drawData.getCmdListIdxBufferData(n));
        }

        // TODO viewport

        if (data.projectionMatrixBuffer == null) {
            data.projectionMatrixBuffer = new CachedImguiOrthoBuffer(-1.0F, 1.0F);
        }
        final GpuBufferSlice projectionMatrixBuffer = data.projectionMatrixBuffer.getBuffer(commandEncoder,L, R, B, T);

        final GpuTextureView colorTexture = renderTarget.getColorTextureView();
        final int width = colorTexture.getWidth(0);
        final int height = colorTexture.getHeight(0);
        try (final RenderPass renderPass = commandEncoder.createRenderPass(
                () -> "ImGui",
                colorTexture,
                clearColor)) {
            renderPass.setPipeline(compiled);
            renderPass.setUniform("Projection", projectionMatrixBuffer);

            // Render command lists
            for (int n = 0; n < cmdListsCount; n++) {
                final GpuBuffer vertexBuffer = data.vertexData.get(n);
                final GpuBuffer indexBuffer = data.indexData.get(n);

                
                renderPass.setVertexBuffer(0, vertexBuffer.slice());
                renderPass.setIndexBuffer(indexBuffer, data.elementSize == 2 ? com.mojang.renderpearl.api.pipeline.IndexType.SHORT : com.mojang.renderpearl.api.pipeline.IndexType.INT);
                
                



                final int cmdBufferSize = drawData.getCmdListCmdBufferSize(n);
                for (int cmdIdx = 0; cmdIdx < cmdBufferSize; cmdIdx++) {
                    drawData.getCmdListCmdBufferClipRect(this.props.clipRect, n, cmdIdx);

                    final float clipMinX = (this.props.clipRect.x - clipOffX) * clipScaleX;
                    final float clipMinY = (this.props.clipRect.y - clipOffY) * clipScaleY;
                    final float clipMaxX = (this.props.clipRect.z - clipOffX) * clipScaleX;
                    final float clipMaxY = (this.props.clipRect.w - clipOffY) * clipScaleY;

                    if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) {
                        continue;
                    }

                    // Match the bottom-left RenderTarget projection before native surface presentation.
                    // Clamp both edges: a window partly offscreen must not widen its visible clip.
                    final int minX = Math.clamp((int) Math.floor(clipMinX), 0, width);
                    final int minY = Math.clamp((int) Math.floor(clipMinY), 0, height);
                    final int maxX = Math.clamp((int) Math.ceil(clipMaxX), 0, width);
                    final int maxY = Math.clamp((int) Math.ceil(clipMaxY), 0, height);
                    if (maxX <= minX || maxY <= minY) {
                        continue;
                    }

                    renderPass.enableScissor(minX, height - maxY, maxX - minX, maxY - minY);

                    // Bind texture, Draw
                    final long textureId = drawData.getCmdListCmdBufferTextureId(n, cmdIdx);
                    final int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(n, cmdIdx);
                    final int idxOffset = drawData.getCmdListCmdBufferIdxOffset(n, cmdIdx);
                    final int elemCount = drawData.getCmdListCmdBufferElemCount(n, cmdIdx);

                    if (textureId == 0) {
                        throw new IllegalStateException("Texture ID is 0");
                    }

                    
                    final com.mojang.renderpearl.api.textures.GpuSampler defaultSampler = RenderSystem.getSamplerCache().getSampler(
                            com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                            com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                            com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                            com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                            true);
                    final com.mojang.renderpearl.api.textures.GpuSampler sampler = textureId == 1 ? defaultSampler : this.data.samplers.get((int) (textureId - 2));
                    renderPass.setUniform("Texture", textureId == 1 ? this.data.fontTextureView : this.data.textures.get((int) (textureId - 2)), sampler != null ? sampler : defaultSampler);
                    
                    


                    
                    renderPass.drawIndexed(elemCount, 1, idxOffset, vtxOffset, 0);
                     
                    

                }
            }
        }
        commandEncoder.submit();
    }

    @Override
    public void free() {
        final ImGuiIO io = ImGui.getIO();

        this.shutdownPlatformInterface();
        this.destroyDeviceObjects();

        io.setBackendRendererName(null);
        io.removeBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset | ImGuiBackendFlags.RendererHasViewports);
        this.data = null;
        releaseRetired();
    }

    @Override
    public void newFrame() {
        if (this.data.fontTexture == null) {
            this.createFontsTexture();
        }
    }

    @Override
    public void renderDrawData(final ImDrawData drawData, final RenderTarget renderTarget) {
        if (this.data.mainViewportData == null) {
            this.data.mainViewportData = new ViewportData();
        }
        this.data.mainViewportData.renderTarget = renderTarget;
        
        this.renderDrawData(drawData, this.data.mainViewportData, Optional.empty());
         
        

    }

    @Override
    public void postDraw() {
        try{this.clearTextures();}finally{releaseRetired();}
    }

    @Override
    public void discard() {
        this.clearTextures();
        releaseRetired();
        if (this.data.mainViewportData != null) {
            this.data.mainViewportData.clearVertexData(0);
        }
    }

    @Override
    public void recreateFontsTexture() {
        this.destroyFontsTexture();
        this.createFontsTexture();
    }

    @Override
    public long getImGuiId(final ImGuiTextureProvider texture, @Nullable final ImGuiSampler sampler) {
        long id = texture.imguimc$id();

        if (id == 0 || sampler != null) {
            final GpuTextureView view = switch (texture) {
                case final AbstractTexture abstractTexture -> abstractTexture.getTextureView();
                case final GpuTextureView gpuTextureView -> gpuTextureView;
                default -> throw new IllegalArgumentException("Unexpected value: " + texture);
            };
            
            id = ((ImGuiRenderImplRenderSystem) ImGuiMCImpl.handler.getRenderer()).addTexture(view, (com.mojang.renderpearl.api.textures.GpuSampler) sampler);
            
            

            if(sampler==null)texture.imguimc$setId(id);
        }

        return id;
    }

    public void createFontsTexture() {
        final ImFontAtlas fontAtlas = ImGui.getIO().getFonts();

        // Build texture atlas
        // Load as RGBA 32-bit (75% of the memory is wasted, but default font is so small) because it is more likely to be compatible with user's existing shaders.
        // If your ImTextureId represent a higher-level concept than just a GL texture id, consider calling GetTexDataAsAlpha8() instead to save on GPU memory.
        final ImInt width = new ImInt();
        final ImInt height = new ImInt();
        
        final java.nio.ByteBuffer pixels = fontAtlas.getTexDataAsRGBA32(width, height);
        
        


        // TODO use GetTexDataAsAlpha8 instead

        final GpuDevice device = RenderSystem.getDevice();
        this.data.fontTexture = device.createTexture(
                "ImGui Font Atlas",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                
                

                com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,
                 
                width.get(),
                height.get(),
                1,
                1);
        
        final var upload=device.createCommandEncoder();upload.writeToTexture(
                this.data.fontTexture,
                pixels,
                0,
                0,
                0,
                0,
                width.get(),
                height.get()
        );
        
        











        upload.submit();
        this.data.fontTextureView = device.createTextureView(this.data.fontTexture);

        // Store our identifier
        fontAtlas.setTexID(1);
    }

    public void destroyFontsTexture() {
        final ImGuiIO io = ImGui.getIO();
        if (this.data.fontTextureView != null) {
            this.data.fontTextureView.close();
            this.data.fontTextureView = null;
        }
        if (this.data.fontTexture != null) {
            this.data.fontTexture.close();
            io.getFonts().setTexID(0);
            this.data.fontTexture = null;
        }
    }

    private void destroyDeviceObjects() {
        if (this.data.mainViewportData != null) {
            this.data.mainViewportData.free();
            this.data.mainViewportData = null;
        }
        this.destroyFontsTexture();
        pipelines.values().forEach(com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline::close);pipelines.clear();
    }

    //--------------------------------------------------------------------------------------------------------
    // MULTI-VIEWPORT / PLATFORM INTERFACE SUPPORT
    // This is an _advanced_ and _optional_ feature, allowing the backend to create and handle multiple viewports simultaneously.
    // If you are new to dear imgui or creating a new binding for dear imgui, it is recommended that you completely ignore this section first..
    //--------------------------------------------------------------------------------------------------------

    private final class RendererRenderWindowFunction extends ImPlatformFuncViewport {

        
        private static final org.joml.Vector4fc CLEAR_COLOR = new org.joml.Vector4f();
         

        @Override
        public void accept(final ImGuiViewport vp) {
            
            final Optional<org.joml.Vector4fc> clearColor = vp.hasFlags(ImGuiViewportFlags.NoRendererClear) ? Optional.empty() : Optional.of(CLEAR_COLOR);
             
            


            final ViewportData data = ImGuiMCImpl.getRenderData(vp, ViewportData::new);
            if (data != null) {
                data.updateRenderTarget(vp);
                ImGuiRenderImplRenderSystem.this.renderDrawData(vp.getDrawData(), data, clearColor);
            }
        }
    }

    protected void initPlatformInterface() {
        ImGui.getPlatformIO().setRendererRenderWindow(new RendererRenderWindowFunction());
    }

    protected void shutdownPlatformInterface() {
        ImGui.destroyPlatformWindows();
    }
}
