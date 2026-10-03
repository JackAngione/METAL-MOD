package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.metalcraft.client.shader.wind.SectionCompilerResultsWind;
import dev.metalcraft.client.shader.wind.WindVertexMetadata;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Classify the block once, then capture only its emitted vertices in the original mesh order. */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerWindMixin {
    @Unique private final ThreadLocal<Capture> metalcraft$windCapture = new ThreadLocal<>();

    @WrapMethod(method = "compile")
    private SectionCompiler.Results metalcraft$captureWind(SectionPos pos, RenderSectionRegion region,
        VertexSorting sorting, SectionBufferBuilderPack builders, Operation<SectionCompiler.Results> original) {
        Capture previous = this.metalcraft$windCapture.get();
        Capture capture = new Capture();
        this.metalcraft$windCapture.set(capture);
        try {
            var results = original.call(pos, region, sorting, builders);
            try {
                Map<ChunkSectionLayer, WindVertexMetadata> finished = new EnumMap<>(ChunkSectionLayer.class);
                capture.layers.forEach((layer, metadata) ->
                    finished.put(layer, metadata.finish(results.renderedLayers.get(layer))));
                ((SectionCompilerResultsWind)(Object)results).metalcraft$setWindVertexMetadata(finished);
                return results;
            } catch (RuntimeException | Error error) {
                results.release();
                throw error;
            }
        } finally {
            if (previous == null) this.metalcraft$windCapture.remove();
            else this.metalcraft$windCapture.set(previous);
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock("
        + "Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void metalcraft$identifyWind(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z,
        BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
        Capture capture = this.metalcraft$windCapture.get();
        int previous = capture.kind;
        float previousRoot = capture.root;
        capture.kind = WindVertexMetadata.kind(state);
        capture.root = y;
        try { original.call(renderer, output, x, y, z, level, pos, state, model, seed); }
        finally { capture.kind = previous; capture.root = previousRoot; }
    }

    // Both vanilla and Fabric's alternate model renderer obtain their actual output builder
    // here. Mark contiguous plant ranges, then read the emitted positions once at publication.
    @Inject(method = "getOrBeginLayer", at = @At("RETURN"))
    private void metalcraft$windLayer(Map<ChunkSectionLayer, BufferBuilder> builders, SectionBufferBuilderPack pack,
        ChunkSectionLayer layer, CallbackInfoReturnable<BufferBuilder> callback) {
        Capture capture = this.metalcraft$windCapture.get();
        if (capture == null) return;
        Layer range = capture.layers.get(layer);
        if (range == null) {
            if (capture.kind == WindVertexMetadata.NONE) return;
            range = new Layer();
            capture.layers.put(layer, range);
        }
        range.mark(((BufferBuilderAccessor)callback.getReturnValue()).metalcraft$vertices(), capture.kind, capture.root);
    }

    @Unique private static final class Capture {
        final Map<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
        int kind;
        float root;
    }

    @Unique private record Span(int first, int end, int kind, float root) { }

    @Unique private static final class Layer {
        final List<Span> spans = new ArrayList<>();
        int first, kind;
        float root;

        void mark(int vertex, int nextKind, float nextRoot) {
            // Leaf motion uses world position, not a block-local root.
            if (nextKind == WindVertexMetadata.LEAVES) nextRoot = 0;
            if (kind == nextKind && root == nextRoot) return;
            if (kind != WindVertexMetadata.NONE && vertex > first) spans.add(new Span(first, vertex, kind, root));
            first = vertex;
            kind = nextKind;
            root = nextRoot;
        }

        WindVertexMetadata finish(MeshData mesh) {
            int count = mesh.drawState().vertexCount();
            mark(count, WindVertexMetadata.NONE, 0);
            var output = new WindVertexMetadata.Builder();
            var vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            int stride = mesh.drawState().format().getVertexSize();
            int start = vertices.position();
            for (Span span : spans) for (int i = span.first(); i < span.end(); i++) {
                output.put(i, span.kind(), vertices.getFloat(start + i * stride + 4) - span.root());
            }
            return output.build(count);
        }
    }
}
