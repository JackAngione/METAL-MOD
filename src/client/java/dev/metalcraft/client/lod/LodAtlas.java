package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable atlas index published after upload. Compiler workers never retain live sprites. */
public final class LodAtlas {
    private static final int GRID = 64;
    private static volatile LodAtlas current = new LodAtlas(List.of());
    private final List<List<LodBakedMesh.Sprite>> buckets;
    private final String fingerprint;

    public LodAtlas(List<LodBakedMesh.Sprite> sprites) {
        fingerprint = LodDistantStore.digest(sprites.stream().sorted(java.util.Comparator.comparing(LodBakedMesh.Sprite::name))
                .map(Object::toString).collect(java.util.stream.Collectors.joining("\n")));
        if (sprites.size() > 65536) throw new IllegalArgumentException("LOD atlas sprite limit exceeded");
        var cells = new ArrayList<List<LodBakedMesh.Sprite>>(GRID * GRID);
        for (int i = 0; i < GRID * GRID; i++) cells.add(new ArrayList<>());
        long references = 0;
        for (var sprite : sprites) {
            for (int y = cell(sprite.v0()); y <= cell(sprite.v1()); y++) {
                for (int x = cell(sprite.u0()); x <= cell(sprite.u1()); x++) {
                    if (++references > 1_048_576) throw new IllegalArgumentException("LOD atlas index budget exceeded");
                    cells.get(x + GRID * y).add(sprite);
                }
            }
        }
        buckets = cells.stream().map(List::copyOf).toList();
    }

    private static int cell(float uv) { return Math.clamp((int)(uv * GRID), 0, GRID - 1); }
    public static LodAtlas current() { return current; }
    public static void publish(LodAtlas atlas) { current = java.util.Objects.requireNonNull(atlas); }
    public static void clear() { current = new LodAtlas(List.of()); }
    public String fingerprint() { return fingerprint; }

    /** Require one real sprite containing the entire emitted footprint; ambiguous/custom UVs decline. */
    public LodBakedMesh.@Nullable Sprite resolve(float u0, float v0, float u1, float v1) {
        if (!(u0 >= 0 && v0 >= 0 && u1 <= 1 && v1 <= 1 && u1 > u0 && v1 > v0)) return null;
        LodBakedMesh.Sprite found = null;
        for (var sprite : buckets.get(cell((u0 + u1) * .5f) + GRID * cell((v0 + v1) * .5f))) {
            if (u0 < sprite.u0() || v0 < sprite.v0() || u1 > sprite.u1() || v1 > sprite.v1()) continue;
            if (found != null) return null;
            found = sprite;
        }
        return found;
    }
}
