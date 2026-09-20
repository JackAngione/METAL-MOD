package dev.metalcraft.client.metal;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;

/** Optional bounded GPU probe using Minecraft's actual terrain material on a covered plane. */
public final class TerrainResolutionBenchmark {
    private static final String VERTEX = """
        #version 450
        out float sphericalVertexDistance;
        out float cylindricalVertexDistance;
        out vec4 vertexColor;
        out vec2 texCoord0;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.2 + p.x * 0.2, 1.0);
            texCoord0 = p * 16.0;
            vertexColor = vec4(0.8, 0.9, 0.7, 1.0);
            sphericalVertexDistance = 200.0;
            cylindricalVertexDistance = 200.0;
        }
        """;
    public static void main(String[] args) {
        var info = RenderPipelines.SOLID_TERRAIN;
        String fragment = Blaze3DMetalMappings.shaderWithResourceBindings(GlslPreprocessor.injectDefines(
            LinearWorldShadersSmoke.expanded(info.getFragmentShader(),ShaderType.FRAGMENT), info.getShaderDefines()),info);
        try (var device = MetalNative.openDefaultDevice().orElseThrow(); var queue = device.createCommandQueue();
             var ordinary = pipeline(device,fragment); var reconstruct = pipeline(device,MetalTerrainResolution.reconstructFragment(fragment));
             var target = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,3840,2160,1));
             var depth = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT,3840,2160,1));
             var texture = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,256,256,5));
             var view = texture.createView();
             var targets = new MetalTerrainResolution(device)) {
            for(int mip=0;mip<5;mip++) {
                int size=256>>mip;
                var bytes=ByteBuffer.allocateDirect(size*size*4);
                for(int i=0;i<size*size;i++) bytes.putInt((i*1103515245)|255);
                texture.upload(queue,mip,bytes.flip());
            }
            var uniformLayout = List.copyOf(com.mojang.blaze3d.pipeline.BindGroupLayout.flattenUniforms(info.getBindGroupLayouts()));
            var uniforms = new ArrayList<MetalBuffer>();
            try {
                for(var uniform:uniformLayout) {
                    var buffer=device.createBuffer(256,MetalBuffer.StorageMode.SHARED); uniforms.add(buffer);
                    try(var map=buffer.map()) {
                        var bytes=map.bytes();
                        for(int i=0;i<256;i+=4) bytes.putInt(i,0);
                        switch(uniform.name()) {
                            case "Globals" -> bytes.putFloat(32,3840).putFloat(36,2160).putInt(52,1);
                            case "Fog" -> bytes.putFloat(16,500).putFloat(20,2000).putFloat(24,500).putFloat(28,2000);
                            case "ChunkSection" -> bytes.putFloat(64,1).putInt(72,256).putInt(76,256);
                        }
                    }
                }
                for(int rgss: new int[]{0,1}) {
                    try(var mapping=uniforms.get(index(uniformLayout,"Globals")).map()) { mapping.bytes().putInt(52,rgss); }
                    for(int tier:new int[]{1,2}) {
                      try (var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,MetalSampler.Filter.NEAREST,
                              MetalSampler.AddressMode.REPEAT,MetalSampler.AddressMode.REPEAT,1,4,tier))) {
                        var band=targets.band(tier,3840,2160,MetalTexture.Format.RGBA8_UNORM);
                        var samples = new ArrayList<Double>(); var reduced = new ArrayList<Double>();
                        for(int pair=0;pair<41;pair++) for(int mode=0;mode<2;mode++) {
                            boolean coarse=(pair%2==0?mode:1-mode)==1;
                            MetalGpuFrameCapture.beginCapture(); MetalGpuFrameCapture.beginFrame();
                            try(var commands=queue.createCommandBuffer()) {
                                var clear = new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target,.1,.2,.3,1),
                                    new MetalRenderPass.DepthAttachment(depth,MetalRenderPass.LoadAction.CLEAR,MetalRenderPass.StoreAction.STORE,0));
                                try(var pass=commands.beginRenderPass(clear)) {
                                    if(!coarse) draw(pass,ordinary,uniforms,view,sampler,null);
                                }
                                if(coarse) {
                                    try(var pass=commands.beginRenderPass(band.descriptor)) { draw(pass,ordinary,uniforms,view,sampler,null); }
                                    var load = new MetalRenderPass.Descriptor(new MetalRenderPass.ColorAttachment(target,MetalRenderPass.LoadAction.LOAD,MetalRenderPass.StoreAction.STORE,0,0,0,0),
                                        new MetalRenderPass.DepthAttachment(depth,MetalRenderPass.LoadAction.LOAD,MetalRenderPass.StoreAction.STORE,0));
                                    try(var pass=commands.beginRenderPass(load)) { draw(pass,reconstruct,uniforms,view,sampler,band); }
                                }
                                commands.commitAndWait();
                            }
                            MetalGpuFrameCapture.endFrame();
                            var result=MetalGpuFrameCapture.endCapture();
                            if(pair>=10) (coarse?reduced:samples).add(result.p50Ms());
                        }
                        samples.sort(Double::compareTo); reduced.sort(Double::compareTo);
                        double base=samples.get(samples.size()/2), low=reduced.get(reduced.size()/2);
                        System.out.printf("4K actual vanilla terrain material, RGSS=%d scale=1/%d native=%.4f ms reduced=%.4f ms change=%.1f%% samples=%s reducedSamples=%s%n",
                            rgss,1<<tier,base,low,(low/base-1)*100,samples,reduced);
                      }
                    }
                }
            } finally { uniforms.forEach(MetalBuffer::close); }
        }
    }
    private static int index(List<com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription> layout,String name) {
        for(int i=0;i<layout.size();i++) if(layout.get(i).name().equals(name)) return i;
        throw new AssertionError(name);
    }
    private static void draw(MetalRenderPass pass,MetalRenderPipeline pipeline,List<MetalBuffer> uniforms,
                             MetalTextureView view,MetalSampler sampler,MetalTerrainResolution.Band band) {
        pass.setPipeline(pipeline);
        for(int i=0;i<uniforms.size();i++) pass.setUniformBuffer(i,uniforms.get(i),0,MetalRenderPass.STAGE_ALL);
        // Sampler0 is the first terrain sampler after uniform slots.
        pass.setTexture(uniforms.size(),view,MetalRenderPass.STAGE_FRAGMENT);
        pass.setSampler(uniforms.size(),sampler,MetalRenderPass.STAGE_FRAGMENT);
        if(band!=null) {
            pass.setTexture(14,band.colorView,MetalRenderPass.STAGE_FRAGMENT);
            pass.setTexture(15,band.depthView,MetalRenderPass.STAGE_FRAGMENT);
            pass.setUniformBuffer(15,band.parameters,0,MetalRenderPass.STAGE_FRAGMENT);
        }
        pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
    }
    private static MetalRenderPipeline pipeline(MetalDevice device,String fragment) {
        var shaders=MetalShaderTranslator.translatePipeline(VERTEX,"benchmark.vsh",fragment,"benchmark.fsh");
        return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(shaders.vertex().metalSource(),shaders.vertex().entryPoint(),
            shaders.fragment().metalSource(),shaders.fragment().entryPoint(),List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)),
            MetalTexture.Format.DEPTH32_FLOAT,MetalRenderPipeline.VertexDescriptor.EMPTY,
            new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.GREATER,0,0),MetalRenderPipeline.RasterState.DEFAULT));
    }
}
