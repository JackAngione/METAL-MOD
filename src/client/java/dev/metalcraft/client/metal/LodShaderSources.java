package dev.metalcraft.client.metal;

import net.minecraft.resources.Identifier;

/** Source-verified no-pack terrain variant. The sidecar changes only merged-face atlas sampling. */
final class LodShaderSources {
    private LodShaderSources() { }
    static String vertex(Identifier id, String source) {
        LinearWorldShaders.verify(id, ".vsh", source);
        String declarations = """
            struct McLodVertex { vec4 mapU; vec4 mapV; vec4 bounds; };
            layout(std430, binding = 14) readonly buffer McLodVertices { McLodVertex mcLodVertices[]; };
            flat out vec4 mcLodMapU;
            flat out vec4 mcLodMapV;
            flat out vec4 mcLodBounds;
            """;
        return replace(source, "void main() {", declarations + """
            void main() {
                McLodVertex lod = mcLodVertices[gl_VertexID];
                mcLodMapU = lod.mapU;
                mcLodMapV = lod.mapV;
                mcLodBounds = lod.bounds;
            """).replaceFirst("#version 330", "#version 450");
    }

    static String fragment(Identifier id, String source) {
        LinearWorldShaders.verify(id, ".fsh", source);
        String helper = """
            flat in vec4 mcLodMapU;
            flat in vec4 mcLodMapV;
            flat in vec4 mcLodBounds;
            vec4 mcLodLevel(sampler2D atlas, vec2 uv, vec4 bounds, float lod) {
                vec2 extent = vec2(textureSize(atlas, 0));
                vec2 inset = min(0.5 * exp2(lod) / extent, (bounds.zw - bounds.xy) * 0.5);
                return textureLod(atlas, clamp(uv, bounds.xy + inset, bounds.zw - inset), lod);
            }
            vec4 mcLodSample(sampler2D atlas, vec2 repeatUV) {
                vec2 continuous = mcLodMapU.xy + repeatUV.x * mcLodMapU.zw + repeatUV.y * mcLodMapV.xy;
                vec2 dx = dFdx(continuous), dy = dFdy(continuous);
                vec2 extent = vec2(textureSize(atlas, 0));
                float footprint = sqrt(max(length(dx * extent) * length(dy * extent), 1.0));
                vec2 tileSize = (mcLodBounds.zw - mcLodBounds.xy) * extent;
                float maxLod = min(float(textureQueryLevels(atlas) - 1), floor(log2(max(min(tileSize.x, tileSize.y), 1.0))));
                float lod = clamp(log2(footprint), 0.0, maxLod);
                vec2 local = fract(repeatUV);
                vec2 uv = mcLodMapU.xy + local.x * mcLodMapU.zw + local.y * mcLodMapV.xy;
                return mix(mcLodLevel(atlas, uv, mcLodBounds, floor(lod)),
                           mcLodLevel(atlas, uv, mcLodBounds, min(ceil(lod), maxLod)), fract(lod));
            }
            """;
        String adapted = replace(source, "void main() {", helper + "\nvoid main() {");
        return replace(adapted, "(UseRgss == 1 ? sampleRGSS", "(mcLodMapV.z != 0.0 ? mcLodSample(Sampler0, texCoord0) : UseRgss == 1 ? sampleRGSS")
                .replaceFirst("#version 330", "#version 450");
    }

    private static String replace(String source, String from, String to) {
        int index = source.indexOf(from);
        if (index < 0 || source.indexOf(from, index + from.length()) >= 0)
            throw new IllegalArgumentException("Unsupported LOD terrain source layout");
        return source.substring(0, index) + to + source.substring(index + from.length());
    }
}
