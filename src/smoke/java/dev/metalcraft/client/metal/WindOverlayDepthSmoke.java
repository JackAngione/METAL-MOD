package dev.metalcraft.client.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;

/** Grass-block overlays must have bit-identical depth with and without a wind sidecar. */
final class WindOverlayDepthSmoke {
    private static final int SIZE = 256, BASE = 3, STRIDE = 28;
    private static final int STAGES = MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT;

    static void run(MetalDevice device, MetalRenderPipeline.Descriptor solidDescriptor,
            MetalRenderPipeline.Descriptor windDescriptor) {
        String windSource = windDescriptor.vertexSource();
        // The production terrain program, compiled both ways; no replacement position math.
        String ordinarySource = solidDescriptor.vertexSource().replace("#define MC_TERRAIN_WIND 1\n", "");
        if (ordinarySource.equals(solidDescriptor.vertexSource())) throw new AssertionError("Missing wind variant define");
        try (var queue = device.createCommandQueue();
             var ordinary = device.createRenderPipeline(descriptor(solidDescriptor, ordinarySource));
             var wind = device.createRenderPipeline(descriptor(windDescriptor, windSource));
             var scene = texture(device, MetalTexture.Format.RGBA16_FLOAT);
             var albedo = texture(device, MetalTexture.Format.RGBA8_UNORM);
             var normal = texture(device, MetalTexture.Format.RGBA8_UNORM);
             var light = texture(device, MetalTexture.Format.RGBA8_UNORM);
             var depth = texture(device, MetalTexture.Format.DEPTH32_FLOAT);
             var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,1,1,1));
             var atlasView = atlas.createView();
             var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
                 MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var vertices = device.createBuffer((BASE+4)*STRIDE,MetalBuffer.StorageMode.SHARED);
             var indices = device.createBuffer(12,MetalBuffer.StorageMode.SHARED);
             var metadata = device.createBuffer(16,MetalBuffer.StorageMode.SHARED);
             var draw = device.createBuffer(16,MetalBuffer.StorageMode.SHARED);
             var projection = device.createBuffer(64,MetalBuffer.StorageMode.SHARED);
             var section = device.createBuffer(96,MetalBuffer.StorageMode.SHARED);
             var globals = device.createBuffer(64,MetalBuffer.StorageMode.SHARED);
             var fog = device.createBuffer(48,MetalBuffer.StorageMode.SHARED)) {
            for (var buffer : List.of(vertices,metadata,draw,section,globals,fog)) {
                try (var mapping = buffer.map()) {
                    while (mapping.bytes().hasRemaining()) mapping.bytes().put((byte)0);
                }
            }
            atlas.upload(queue,0,ByteBuffer.allocateDirect(4).putInt(-1).flip());
            try (var mapping = vertices.map()) {
                var b = mapping.bytes();
                for (int i=0;i<4;i++) {
                    int p=(BASE+i)*STRIDE;
                    b.putFloat(p,i==0||i==3?8:9).putFloat(p+4,i>=2?9:8).putFloat(p+8,8)
                        .putInt(p+12,-1).putFloat(p+16,.5F).putFloat(p+20,.5F)
                        .putShort(p+24,(short)240).putShort(p+26,(short)240);
                }
            }
            try (var mapping = indices.map()) {
                for (int i : new int[]{0,1,2,0,2,3}) mapping.bytes().putShort((short)i);
            }
            try (var mapping = draw.map()) { mapping.bytes().putInt(BASE).putInt(4).putFloat(17.25F).putFloat(0); }
            try (var mapping = section.map()) {
                mapping.bytes().putFloat(64,1).putInt(72,1).putInt(76,1)
                    .putInt(80,32).putInt(84,64).putInt(88,-48);
            }
            try (var mapping = globals.map()) { mapping.bytes().putInt(0,40).putInt(4,72).putInt(8,-38); }
            try (var mapping = fog.map()) { for (int i=16;i<48;i+=4) mapping.bytes().putFloat(i,1000); }
            var passDescriptor = new MetalRenderPass.Descriptor(List.of(
                MetalRenderPass.ColorAttachment.clear(scene,0,0,0,0),
                MetalRenderPass.ColorAttachment.clear(albedo,0,0,0,0),
                MetalRenderPass.ColorAttachment.clear(normal,0,0,0,0),
                MetalRenderPass.ColorAttachment.clear(light,0,0,0,0)),
                new MetalRenderPass.DepthAttachment(depth,MetalRenderPass.LoadAction.CLEAR,MetalRenderPass.StoreAction.STORE,1),0);
            int differences=0, covered=0;
            for (int turn=0;turn<16;turn++) {
                try (var mapping = projection.map()) {
                    // Minecraft uses reverse Z, which exposes small position differences
                    // that a conventional depth buffer rounds away near one.
                    new Matrix4f().perspective(1.2F+turn*.003F,1,256,.05F,true)
                        .translate(.017F,-.031F,0).rotateZ(.037F).rotateX(.019F).get(0,mapping.bytes());
                }
                try (var mapping = section.map()) {
                    new Matrix4f().rotateYXZ((turn-8)*.051F,-.27F+turn*.031F,.07F).get(0,mapping.bytes());
                }
                try (var mapping = globals.map()) {
                    mapping.bytes().putFloat(16,-.375F-turn*.0013F).putFloat(20,-.251372F).putFloat(24,-.125733F);
                }
                ByteBuffer reference = null;
                for (var pipeline : List.of(ordinary,wind)) {
                    try (var commands = queue.createCommandBuffer()) {
                        try (var pass = commands.beginRenderPass(passDescriptor)) {
                            pass.setPipeline(pipeline); pass.setVertexBuffer(16,vertices,0);
                            pass.setUniformBuffer(slot(windSource,"PROJECTION"),projection,0,STAGES);
                            pass.setUniformBuffer(slot(windSource,"TRANSFORMS"),section,0,STAGES);
                            pass.setUniformBuffer(slot(windSource,"GLOBALS"),globals,0,STAGES);
                            pass.setUniformBuffer(slot(windSource,"FOG"),fog,0,STAGES);
                            pass.setUniformBuffer(14,metadata,0,STAGES); pass.setUniformBuffer(15,draw,0,STAGES);
                            for (String name : List.of("SAMPLER0","SAMPLER2")) {
                                pass.setTexture(slot(windSource,name),atlasView,STAGES);
                                pass.setSampler(slot(windSource,name),sampler,STAGES);
                            }
                            pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE,indices,0,
                                MetalRenderPass.IndexType.UINT16,6,1,BASE,0);
                        }
                        commands.commitAndWait();
                    }
                    var pixels=depth.readback(queue,0).order(ByteOrder.nativeOrder());
                    if (reference==null) reference=pixels;
                    else for (int i=0;i<SIZE*SIZE;i++) {
                        if (reference.getFloat(i*4)<1) covered++;
                        if (reference.getInt(i*4)!=pixels.getInt(i*4)) differences++;
                    }
                }
            }
            System.out.println("Grass overlay wind depth: mismatches="+differences+", covered="+covered);
            if (covered<10000 || differences!=0) throw new AssertionError("Wind changes stationary grass-block overlay depth");
        }
    }

    private static MetalTexture texture(MetalDevice device, MetalTexture.Format format) {
        return device.createTexture(new MetalTexture.Descriptor(format,SIZE,SIZE,1));
    }

    private static MetalRenderPipeline.Descriptor descriptor(MetalRenderPipeline.Descriptor original, String source) {
        return new MetalRenderPipeline.Descriptor(source,original.vertexFunction(),source,original.fragmentFunction(),
            original.colorTargets(),MetalTexture.Format.DEPTH32_FLOAT,original.vertexDescriptor(),
            new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.ALWAYS,0,0),
            new MetalRenderPipeline.RasterState(MetalRenderPipeline.CullMode.NONE,MetalRenderPipeline.FillMode.FILL));
    }

    private static int slot(String source,String name) {
        var match=java.util.regex.Pattern.compile("#define MC_SLOT_"+name+" (\\d+)").matcher(source);
        if (!match.find()) throw new AssertionError("Missing slot "+name);
        return Integer.parseInt(match.group(1));
    }
}
