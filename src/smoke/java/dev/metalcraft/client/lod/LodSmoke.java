package dev.metalcraft.client.lod;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic correctness fixtures; no Minecraft bootstrap or GPU is needed. */
public final class LodSmoke {
    private static final TerrainSnapshot.Material AIR = new TerrainSnapshot.Material(0, TerrainSnapshot.Policy.EMPTY, -1, 0);
    private static final TerrainSnapshot.Material STONE = new TerrainSnapshot.Material(1, TerrainSnapshot.Policy.OPAQUE_CUBE, -1, 0);
    private static final TerrainSnapshot.Material TINTED = new TerrainSnapshot.Material(1, TerrainSnapshot.Policy.OPAQUE_CUBE, 0xff336633, 15);
    private static final TerrainSnapshot.Material THIN = new TerrainSnapshot.Material(2, TerrainSnapshot.Policy.UNSUPPORTED, -1, 0);
    private static final TerrainSnapshot.Key KEY = new TerrainSnapshot.Key(1, "overworld", 0, 0, 0, 1, 1);

    public static void main(String[] args) {
        settings();
        geometry();
        selection();
        scheduling();
        residency();
        bakedFaces();
        captureLifecycle();
        atlas();
        LodDistantSmoke.run();
        generationCursor();
        check(LodSettings.defaults().meshBudgetBytes(64L << 30, 1L << 30, 0) == (512L << 20), "automatic working-set budget is capped");
        check(LodSettings.defaults().meshBudgetBytes(1L << 30, 1L << 30, 0) == 1, "other renderer pressure removes admission headroom");
        check(LodSettings.defaults().meshBudgetBytes(0, 1L << 30, 0) == (128L << 20), "unknown working set retains a bounded fallback");
        System.out.println("LOD smoke passed: settings recovery/round trip/gates, exact surface coverage, caves/overhangs/materials/seams, selection and stale/bounded jobs");
    }

    private static void generationCursor() {
        for(int horizon:new int[]{16,32,64,128,256}) {
            var cursor=new LodGenerationCursor(-51,73,16,horizon);
            var seen=new HashSet<LodGenerationCursor.Column>();
            int lastRadius=16;
            for(var next=cursor.next();next!=null;next=cursor.next()) {
                int dx=Math.abs(next.x()+51),dz=Math.abs(next.z()-73),radius=Math.max(dx,dz);
                check(radius>16 && radius>=lastRadius && radius<=horizon,"nearest rings outside loaded square");
                check(seen.add(next),"each generation column occurs once"); lastRadius=radius;
            }
            for(int z=-horizon;z<=horizon;z++) for(int x=-horizon;x<=horizon;x++) {
                long nx=Math.max(0,Math.abs(x)-1),nz=Math.max(0,Math.abs(z)-1);
                boolean expected=Math.max(Math.abs(x),Math.abs(z))>16 && nx*nx+nz*nz<(long)horizon*horizon;
                check(seen.contains(new LodGenerationCursor.Column(x-51,z+73))==expected,"complete bounded horizon coverage");
            }
            check(cursor.next()==null,"completed cursor remains exhausted");
        }
        var saved=LodSettings.defaults().withGeneration(false).withHorizon(64,true,2048).withEnabled(true);
        check(!LodSettingsCodec.read(JsonParser.parseString(new Gson().toJson(saved))).generateTerrain(),"generation disable persists");
        check(LodSettingsCodec.read(JsonParser.parseString("{} ")).generateTerrain(),"old settings acquire generation default");
        check(!saved.withPreset(LodSettings.Preset.QUALITY).generateTerrain(),"presets preserve generation preference");
    }
    private static void atlas() {
        var left = new LodBakedMesh.Sprite("left", 0, 0, .5f, 1);
        var right = new LodBakedMesh.Sprite("right", .5f, 0, 1, 1);
        var atlas = new LodAtlas(List.of(left, right));
        check(atlas.resolve(.01f, .01f, .49f, .99f) == left, "shrunken emitted UVs resolve to real atlas bounds");
        check(atlas.resolve(.51f, .01f, .99f, .99f) == right, "neighbor sprite identity remains distinct");
        check(atlas.resolve(.49f, .1f, .51f, .9f) == null, "footprint crossing atlas sprites declines");
        check(atlas.resolve(Float.NaN, 0, .5f, 1) == null, "nonfinite footprint declines");
        check(new LodAtlas(List.of(left, left)).resolve(.1f, .1f, .4f, .9f) == null, "ambiguous overlapping atlas sprites decline");
        var tracker = new LodRevisionTracker(1);
        var ticket = tracker.capture(0, 0, 0);
        var releases = new java.util.concurrent.atomic.AtomicInteger();
        var payload = new LodBakedMesh.Simplified(true, List.of(), List.of(), 0);
        LodAtlas.publish(atlas);
        var capture = new LodCapturedMesh(ticket, payload, atlas, releases::incrementAndGet);
        LodAtlas.clear();
        check(ticket.current() && capture.currentMesh() == null, "atlas replacement independently invalidates UV ownership");
        capture.close();
        check(releases.get() == 1, "atlas-stale source charge releases exactly once");
    }

    private static void captureLifecycle() {
        var tracker = new LodRevisionTracker(2);
        tracker.world("overworld");
        var first = tracker.capture(1, 2, 3);
        var mesh = new LodBakedMesh.Simplified(true, List.of(), List.of(), 0);
        var releases = new java.util.concurrent.atomic.AtomicInteger();
        var candidate = new LodCapturedMesh(first, mesh, releases::incrementAndGet);
        check(candidate.currentMesh() == mesh, "current captured payload available");
        for (int tier = 1; tier <= 4; tier++)
            check(candidate.residencyTier(tier) == 1 && candidate.mesh(tier) == mesh,
                    "exact shared tiers use one GPU residency owner");
        var other = new LodBakedMesh.Simplified(true, List.of(), List.of(), 0);
        try (var distinct = new LodCapturedMesh(tracker.capture(7, 2, 3),
                List.of(mesh, other, mesh, other), LodAtlas.current(), () -> { })) {
            check(distinct.residencyTier(2) == 2 && distinct.residencyTier(3) == 1
                    && distinct.residencyTier(4) == 2, "only identity-equal meshes alias residency");
        }
        tracker.dirty(1, 2, 3);
        check(!first.current() && candidate.currentMesh() == null, "dirty section revokes captured payload before replacement upload");
        check(releases.get() == 1, "revocation releases CPU bytes without waiting for vanilla mesh retirement");
        candidate.close(); candidate.close();
        check(releases.get() == 1, "captured payload charge released exactly once");
        var second = tracker.capture(1, 2, 3);
        check(second.key().revision() > first.key().revision(), "edits cannot reuse work identity");
        var replacement = tracker.capture(1, 2, 3);
        check(!second.current() && replacement.current(), "new extraction revokes older worker even without dirty callback");
        tracker.capture(4, 2, 3);
        tracker.capture(5, 2, 3);
        check(!replacement.current() && tracker.trackedSections() == 2, "bounded identity eviction revokes outstanding work");
        var unload = tracker.capture(9, -4, 10);
        tracker.unload(9, 10);
        check(!unload.current(), "column unload revokes section work");
        var resource = tracker.capture(9, -4, 10);
        tracker.resources();
        var reloaded = tracker.capture(9, -4, 10);
        check(!resource.current() && reloaded.key().resources() > resource.key().resources(), "resource generation invalidates material coordinates");
        tracker.world("nether");
        var dimension = tracker.capture(9, -4, 10);
        check(!reloaded.current() && dimension.key().session() > reloaded.key().session()
                && dimension.key().dimension().equals("nether"), "world/dimension isolation");
        tracker.world("disconnected");
        check(!dimension.current() && tracker.trackedSections() == 0, "disconnect clears bounded identity table");
        var staleCandidate = new LodCapturedMesh(dimension, mesh, releases::incrementAndGet);
        check(staleCandidate.currentMesh() == null && releases.get() == 2, "revocation racing candidate publication releases immediately");
    }

    private static void bakedFaces() {
        var sprite = new LodBakedMesh.Sprite("minecraft:stone", 0, 0, .5f, .5f);
        var quads = new java.util.ArrayList<LodBakedMesh.Quad>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            quads.add(new LodBakedMesh.Quad(sprite, List.of(
                    new LodBakedMesh.Vertex(x, 8, z, 0, 0, -1, 240),
                    new LodBakedMesh.Vertex(x, 8, z + 1, .5f, 0, -1, 240),
                    new LodBakedMesh.Vertex(x + 1, 8, z + 1, .5f, .5f, -1, 240),
                    new LodBakedMesh.Vertex(x + 1, 8, z, 0, .5f, -1, 240))));
        }
        var snapshot = new LodBakedMesh(quads);
        var merged = snapshot.simplify(4);
        check(merged.supported() && merged.quads() == 61, "actual baked faces merge with unit boundary strips");
        var vertexBytes = java.nio.ByteBuffer.allocate(merged.quads() * 4 * LodWorldMesh.VERTEX_BYTES).order(java.nio.ByteOrder.nativeOrder());
        var metadata = java.nio.ByteBuffer.allocate(merged.quads() * 4 * LodWorldMesh.METADATA_BYTES).order(java.nio.ByteOrder.nativeOrder());
        LodWorldMesh.write(merged, vertexBytes, metadata);
        check(vertexBytes.position() == vertexBytes.capacity() && metadata.position() == metadata.capacity(), "world BLOCK and sidecar byte counts agree");
        int repeated = 0;
        for (int i = 0; i < merged.quads() * 4; i++) {
            int vertex = i * LodWorldMesh.VERTEX_BYTES, meta = i * LodWorldMesh.METADATA_BYTES;
            check(vertexBytes.getFloat(vertex + 4) == 8 && vertexBytes.getInt(vertex + 12) == -1
                    && vertexBytes.getInt(vertex + 24) == 240, "world upload preserves surface plane/tint/light ABI");
            if (metadata.getFloat(meta + 24) == 1) {
                repeated++;
                float u = vertexBytes.getFloat(vertex + 16), v = vertexBytes.getFloat(vertex + 20);
                check(u >= 0 && u <= 14 && v >= 0 && v <= 14, "unwrapped block coordinates bounded by merged face");
                check(metadata.getFloat(meta + 40) == .5f && metadata.getFloat(meta + 44) == .5f, "real atlas rectangle survives upload");
            }
        }
        check(repeated == 4, "one 14x14 inner face repeats; boundary faces retain original samples");
        var tiers = snapshot.simplifyTiers();
        for (int tier = 1; tier <= 4; tier++) {
            check(tiers.get(tier - 1).equals(snapshot.simplify(tier)), "shared classification preserves tier output");
            Set<String> coverage = new HashSet<>();
            for (var rect : tiers.get(tier - 1).rectangles()) {
                for (int v = 0; v < rect.height(); v++) for (int u = 0; u < rect.width(); u++)
                    check(coverage.add((rect.u() + u) + ":" + (rect.v() + v)), "baked face owned once");
            }
            check(coverage.size() == 256, "all emitted baked surfaces preserved");
        }
        var vertices = new java.util.ArrayList<>(quads.getFirst().vertices());
        var first = vertices.getFirst();
        vertices.set(0, new LodBakedMesh.Vertex(first.x(), first.y(), first.z(), first.u(), first.v(), 0xff112233, 16));
        var shaded = new LodBakedMesh.Quad(sprite, vertices);
        var exact = new LodBakedMesh(List.of(shaded)).simplify(4);
        check(exact.supported() && exact.unmerged().equals(List.of(shaded)), "AO and light gradients retained verbatim");
        var mixed = new java.util.ArrayList<>(snapshot.quads());
        mixed.set(0, shaded);
        var mixedMesh = new LodBakedMesh(mixed);
        var mixedTiers = mixedMesh.simplifyTiers();
        for (int tier = 1; tier <= 4; tier++) {
            check(mixedTiers.get(tier - 1).equals(mixedMesh.simplify(tier))
                    && mixedTiers.get(tier - 1).unmerged().equals(List.of(shaded)),
                    "shared tiers preserve shaded faces alongside merged surfaces");
        }
        check(new LodBakedMesh(List.of(quads.getFirst(), quads.getFirst())).simplifyTiers().stream()
                .noneMatch(LodBakedMesh.Simplified::supported), "all shared tiers reject overlapping faces");
        vertices.set(0, new LodBakedMesh.Vertex(.25f, first.y(), first.z(), first.u(), first.v(), -1, 240));
        check(!new LodBakedMesh(List.of(new LodBakedMesh.Quad(sprite, vertices))).simplify(4).supported(), "custom/thin models reject section");
        check(!new LodBakedMesh(List.of(quads.getFirst(), quads.getFirst())).simplify(4).supported(), "overlapping model faces reject section");
        quads.clear();
        check(snapshot.quads().size() == 256 && shaded.vertices().getFirst().color() == 0xff112233, "copied baked snapshot owns data");
    }

    private static void settings() {
        LodSettings defaults = LodSettings.defaults();
        Gson gson = new Gson();
        check(LodSettingsCodec.read(gson.toJsonTree(defaults)).equals(defaults), "default round trip");
        LodSettings custom = defaults.withPreset(LodSettings.Preset.QUALITY).withGeometry(8, 1.5).withEnabled(true);
        check(custom.preset() == LodSettings.Preset.CUSTOM, "manual setting clears preset");
        check(LodSettingsCodec.read(JsonParser.parseString(gson.toJson(custom))).equals(custom), "custom serialized reload");
        check(LodSettingsCodec.read(JsonParser.parseString("{}" )).equals(defaults), "missing fields migrate");
        LodSettings malformed = LodSettingsCodec.read(JsonParser.parseString("{\"enabled\":[],\"preset\":\"future\",\"fullDetailChunks\":900,\"errorPixels\":-9,\"horizonChunks\":300,\"meshBudgetMiB\":-4,\"diskBudgetMiB\":999999}"));
        check(!malformed.enabled() && malformed.fullDetailChunks() == 12 && malformed.errorPixels() == .5
                && malformed.horizonChunks() == 16 && malformed.meshBudgetMiB() == 128 && malformed.diskBudgetMiB() == 8192, "malformed fields clamp independently");
        check(LodSettingsCodec.read(JsonParser.parseString("{\"errorPixels\":\"NaN\",\"fullDetailChunks\":1e99}" )).equals(defaults), "non-numeric and overflowing fields recover");
        LodFrameSettings frames = new LodFrameSettings();
        frames.request(custom);
        check(frames.current().equals(defaults), "mid-frame immutable");
        check(frames.beginFrame(LodCapabilities.current(true)).enabled(), "opt-in preview is available without a development flag");
        check(!LodCapabilities.current(true).effective(defaults).enabled(), "preview stays disabled by default");
        var horizon = LodCapabilities.current(true).effective(custom.withHorizon(128, true, 2048));
        check(horizon.enabled() && horizon.horizonChunks() == 128 && horizon.shading() == LodSettings.Shading.FULL,
                "explored horizon available with full-resolution shading");
        frames.request(defaults);
        frames.request(custom);
        check(frames.beginFrame(new LodCapabilities(true, true, false, false)).equals(custom), "latest request coalesced");
        check(!frames.beginFrame(new LodCapabilities(false, true, true, true)).enabled(), "Metal required");
    }

    private interface Cells { int at(int x, int y, int z); }
    private static TerrainSnapshot snapshot(TerrainSnapshot.Key key, Cells cells) {
        int[] values = new int[TerrainSnapshot.VOLUME];
        for (int z = -1; z <= 16; z++) for (int y = -1; y <= 16; y++) for (int x = -1; x <= 16; x++) values[TerrainSnapshot.index(x,y,z)] = cells.at(x,y,z);
        return new TerrainSnapshot(key, List.of(AIR, STONE, TINTED, THIN), values);
    }
    private static boolean inside(int x, int y, int z) { return x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16; }

    private static void geometry() {
        TerrainSnapshot solid = snapshot(KEY, (x,y,z) -> inside(x,y,z) ? 1 : 0);
        TerrainHierarchy.Mesh cube = TerrainHierarchy.build(solid, 4);
        check(cube.root().leaf() && cube.root().size() == 16, "homogeneous hierarchy collapses");
        check(cube.triangles() < cube.exposedUnitFaces(), "more than 50% fewer triangles for solid fixture");
        List<TerrainSnapshot> scenes = List.of(solid,
                snapshot(KEY, (x,y,z) -> inside(x,y,z) && !(y >= 5 && y < 9 && z >= 5 && z < 9) ? 1 : 0),
                snapshot(KEY, (x,y,z) -> inside(x,y,z) && (y == 12 || (x < 3 && z < 3)) ? 1 : 0),
                snapshot(KEY, (x,y,z) -> inside(x,y,z) ? ((x+y+z)%2 == 0 ? 1 : 2) : 0),
                snapshot(KEY, (x,y,z) -> y >= 0 && y < 8 ? 1 : 0));
        for (TerrainSnapshot scene : scenes) {
            Set<String> expected = unitFaces(scene);
            for (int tier = 1; tier <= 4; tier++) {
                TerrainHierarchy.Mesh mesh = TerrainHierarchy.build(scene, tier);
                check(mesh.equals(TerrainHierarchy.build(scene, tier)), "deterministic hierarchy");
                Set<String> actual = new HashSet<>();
                for (var f : mesh.faces()) {
                    if (f.u() == 0 || f.v() == 0 || f.u() == 15 || f.v() == 15) check(f.width() == 1 && f.height() == 1, "unit boundary seams");
                    for (int dv = 0; dv < f.height(); dv++) for (int du = 0; du < f.width(); du++) {
                        check(actual.add(face(f.axis(),f.sign(),f.plane(),f.u()+du,f.v()+dv,f.material())), "exactly one surface owner");
                    }
                }
                check(expected.equals(actual), "all supported surfaces preserved");
            }
        }
        check(!TerrainHierarchy.build(snapshot(KEY, (x,y,z) -> x==4 && y==5 && z==6 ? 3 : 0), 2).supported(), "thin model uses ordinary fallback");
        int[] cells = new int[TerrainSnapshot.VOLUME];
        TerrainSnapshot immutable = new TerrainSnapshot(KEY,List.of(AIR,STONE),cells);
        cells[TerrainSnapshot.index(0,0,0)] = 1;
        check(immutable.at(0,0,0).equals(AIR), "snapshot defensive copy");
        TerrainSnapshot captured = TerrainSnapshot.capture(KEY, (x,y,z) -> solid.at(x,y,z));
        check(TerrainHierarchy.build(captured, 4).equals(cube), "owning-thread capture is identical");
        TerrainSnapshot left = snapshot(KEY, (x,y,z) -> y >= 0 && y < 8 ? 1 : 0);
        TerrainSnapshot right = snapshot(new TerrainSnapshot.Key(1,"overworld",1,0,0,1,1), (x,y,z) -> y >= 0 && y < 8 ? 1 : 0);
        for (int tier=1;tier<4;tier++) {
            check(seam(TerrainHierarchy.build(left,tier),16).equals(seam(TerrainHierarchy.build(right,tier+1),0)), "adjacent tiers have identical boundary vertices");
        }
        System.out.println("Solid fixture: " + cube.exposedUnitFaces()*2 + " -> " + cube.triangles() + " triangles; world error 0");
    }

    private static String face(int axis,int sign,int plane,int u,int v,TerrainSnapshot.Material m) { return axis+":"+sign+":"+plane+":"+u+":"+v+":"+m; }
    private static Set<String> seam(TerrainHierarchy.Mesh mesh, int boundaryX) {
        Set<String> vertices = new HashSet<>();
        for (var f : mesh.faces()) for (int u : new int[]{f.u(), f.u()+f.width()}) for (int v : new int[]{f.v(), f.v()+f.height()}) {
            int x=f.axis()==0?f.plane():f.axis()==1?v:u;
            int y=f.axis()==0?u:f.axis()==1?f.plane():v;
            int z=f.axis()==0?v:f.axis()==1?u:f.plane();
            if(x==boundaryX) vertices.add(y+":"+z);
        }
        return vertices;
    }
    private static Set<String> unitFaces(TerrainSnapshot s) {
        Set<String> result = new HashSet<>();
        for (int z=0; z<16; z++) for (int y=0; y<16; y++) for (int x=0; x<16; x++) {
            var m = s.at(x,y,z);
            if (m.policy()!=TerrainSnapshot.Policy.OPAQUE_CUBE) continue;
            for (int axis=0;axis<3;axis++) for (int sign:new int[]{-1,1}) {
                int nx=x+(axis==0?sign:0), ny=y+(axis==1?sign:0), nz=z+(axis==2?sign:0);
                if(s.at(nx,ny,nz).policy()!=TerrainSnapshot.Policy.EMPTY) continue;
                int depth=axis==0?x:axis==1?y:z, u=axis==0?y:axis==1?z:x, v=axis==0?z:axis==1?x:y;
                result.add(face(axis,sign,depth+(sign>0?1:0),u,v,m));
            }
        }
        return result;
    }

    private static void selection() {
        LodSettings settings = LodSettings.defaults().withEnabled(true);
        double[] errors = {0, .1, .2, .4, .8};
        check(LodSelector.select(false,100,100,.05,1080,1,settings,errors,0)==-1,"frustum first");
        check(LodSelector.select(true,10,10,.05,1080,1,settings,errors,0)==0,"near radius");
        check(LodSelector.select(true,.01,100,.05,1080,1,settings,errors,4)==0,"near-plane safeguard");
        int near=LodSelector.select(true,200,200,.05,1080,1,settings,errors,0);
        int far=LodSelector.select(true,400,400,.05,1080,1,settings,errors,0);
        check(far>=near,"distance permits coarsening");
        check(LodSelector.select(true,200,200,.05,2160,1,settings,errors,0)<=near,"Retina error scale");
        check(LodSelector.select(true,200,200,.05,1080,.3,settings,errors,0)<=near,"zoom retains detail");
        check(LodSelector.select(true,200,200,.05,1080,1,settings,new double[]{0,Double.NaN},4)==0,"missing tiers fallback");
        int[] balanced=LodSelector.balance(new int[]{0,4,4,4,4},new int[][]{{1},{0,2},{1,3},{2,4},{3}});
        check(java.util.Arrays.equals(balanced,new int[]{0,1,2,3,4}),"neighbor tiers differ by at most one");
        check(java.util.Arrays.equals(LodSelector.balance(new int[]{4,0},new int[][]{{1},{}}),new int[]{1,0}),"an adjacency pair constrains both endpoints");
        check(LodSelector.transition(0,4,true)==1 && LodSelector.transition(4,0,true)==0,"bounded coarsening and immediate near-detail restoration");
        check(LodSelector.transition(0,4,false)==4,"smoothing toggle independent of seam enforcement");
        check(java.util.Arrays.equals(LodSelector.balance(new int[]{4,4,0},new int[][]{{1},{},{1}}),
                new int[]{2,1,0}), "one-way adjacency propagates later refinements to earlier dependents");
        int[] masks = {31, 1 | (1 << 4), 31, 31};
        int[][] neighbors = {{1}, {2}, {3}, {}};
        check(java.util.Arrays.equals(LodSelector.resolveLoaded(new int[]{4,4,4,0}, new int[]{4,4,4,0},
                masks, neighbors, false), new int[]{1,0,1,0}), "missing intermediate upload triggers another neighbor refinement");
        check(LodSelector.resolveLoaded(new int[]{4}, new int[]{0}, new int[]{17}, new int[][]{{}}, true)[0] == 0,
                "smoothing cannot jump over a missing uploaded tier");
        check(LodSelector.resolveLoaded(new int[]{2}, new int[]{4}, new int[]{17}, new int[][]{{}}, false)[0] == 0,
                "availability fallback never exceeds screen-error tier");
        check(LodSelector.resolveLoaded(new int[0], new int[0], new int[0],
                LodSelector.adjacency(new int[0][]), true).length == 0, "empty graph has no queue work");
        int[][] chain = new int[1024][];
        int[] coarse = new int[1024], allTiers = new int[1024];
        java.util.Arrays.fill(coarse, 4);
        java.util.Arrays.fill(allTiers, 31);
        coarse[1023] = 0;
        for (int i = 0; i < chain.length; i++) chain[i] = i + 1 < chain.length ? new int[]{i + 1} : new int[0];
        var graph = LodSelector.adjacency(chain);
        var expected = referenceSelection(coarse, coarse, allTiers, chain, false);
        for (int[] edge : chain) java.util.Arrays.fill(edge, 0);
        check(java.util.Arrays.equals(LodSelector.resolveLoaded(coarse, coarse, allTiers, graph, false), expected),
                "queue wraps for late refinement and graph owns its input copy");
        // Random sparse availability and one-way boundary graphs catch cascade/order failures.
        var random = new java.util.Random(431);
        for (int fixture = 0; fixture < 1000; fixture++) {
            int[] candidate = new int[20], previous = new int[20], available = new int[20];
            int[][] edges = new int[20][];
            for (int i = 0; i < 20; i++) {
                candidate[i] = random.nextInt(6) - 1;
                previous[i] = random.nextInt(6) - 1;
                available[i] = random.nextInt(16) * 2 + 1;
                edges[i] = new int[]{random.nextInt(20), random.nextInt(20)};
            }
            var adjacency = LodSelector.adjacency(edges);
            LodSelector.balance(candidate, adjacency);
            int[] result = LodSelector.resolveLoaded(candidate, previous, available, adjacency, true);
            check(java.util.Arrays.equals(result, referenceSelection(candidate, previous, available, edges, true)),
                    "shared graph and ring queue match fixed-point reference");
            check(java.util.Arrays.equals(LodSelector.resolveLoaded(candidate, previous, available, adjacency, false),
                    referenceSelection(candidate, previous, available, edges, false)),
                    "reused graph preserves unsmoothed availability cascades");
            for (int i = 0; i < 20; i++) {
                check(result[i] <= candidate[i], "resolved tier never coarser than error decision");
                check(result[i] < 0 || (available[i] & (1 << result[i])) != 0, "every selected mesh is available");
                check(result[i] <= LodSelector.transition(previous[i], candidate[i], true), "transition bound survives availability");
                for (int neighbor : edges[i]) check(result[i] < 0 || result[neighbor] < 0
                        || Math.abs(result[i] - result[neighbor]) <= 1, "every visible boundary balanced after fallback");
            }
        }
    }

    /** Deliberately simple repeated edge scans, independent of the production FIFO/CSR graph. */
    private static int[] referenceSelection(int[] candidate, int[] previous, int[] available,
            int[][] edges, boolean smoothing) {
        int[] result = new int[candidate.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = LodSelector.transition(previous[i], candidate[i], smoothing);
            while (result[i] > 0 && (available[i] & (1 << result[i])) == 0) result[i]--;
        }
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < result.length; i++) for (int j : edges[i]) {
                if (result[i] < 0 || result[j] < 0 || Math.abs(result[i] - result[j]) <= 1) continue;
                int coarse = result[i] > result[j] ? i : j;
                result[coarse] = Math.min(result[i], result[j]) + 1;
                while (result[coarse] > 0 && (available[coarse] & (1 << result[coarse])) == 0) result[coarse]--;
                changed = true;
            }
        } while (changed);
        return result;
    }

    private static void scheduling() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        LodBuildQueue queue = new LodBuildQueue(jobs::add,1,8*1024*1024);
        TerrainSnapshot s = snapshot(KEY,(x,y,z)->0);
        queue.current(KEY);
        check(queue.submit(s,1),"job admitted");
        check(!queue.submit(s,1) && !queue.submit(s,2),"duplicate and job limits");
        var changed = new TerrainSnapshot.Key(1,"overworld",0,0,0,2,1);
        queue.current(changed);
        jobs.remove().run();
        check(queue.drain(Long.MAX_VALUE).isEmpty() && queue.reservedBytes()==0,"edited stale job discarded");
        queue.current(KEY);
        check(queue.submit(s,1),"resubmit");
        queue.reset(); jobs.remove().run();
        check(queue.drain(Long.MAX_VALUE).isEmpty(),"world reset discards work");
        queue.current(KEY); queue.submit(s,1);
        queue.current(new TerrainSnapshot.Key(1,"overworld",0,0,0,1,2));
        jobs.remove().run();
        check(queue.drain(Long.MAX_VALUE).isEmpty(),"resource generation discards work");
        queue.current(KEY); queue.submit(s,1); queue.unload(KEY); jobs.remove().run();
        check(queue.drain(Long.MAX_VALUE).isEmpty(),"unload discards work");
        queue.current(KEY); queue.submit(s,1); jobs.remove().run();
        check(queue.drain(Long.MAX_VALUE).size()==1,"current job published");
        LodBuildQueue tiny = new LodBuildQueue(jobs::add,4,1);
        tiny.current(KEY);
        check(!tiny.submit(s,1) && tiny.pendingJobs()==0,"snapshot/build budget admission");
        LodBuildQueue failing = new LodBuildQueue(jobs::add,1,8*1024*1024,(snapshot,tier)->{throw new IllegalStateException("fixture");});
        failing.current(KEY); failing.submit(s,1); jobs.remove().run();
        check(failing.drain(0).isEmpty() && failing.failures()==1 && failing.reservedBytes()==0,"failed worker releases budget");
        TerrainSnapshot solid = snapshot(KEY,(x,y,z)->inside(x,y,z)?1:0);
        queue.current(KEY); queue.submit(solid,1); jobs.remove().run();
        check(queue.drain(1).isEmpty() && queue.pendingJobs()==0 && queue.reservedBytes()==0,"oversized upload keeps ordinary fallback and releases reservation");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Resource implements AutoCloseable {
        int closed;
        public void close() { closed++; }
    }

    private static void residency() {
        Resource first=new Resource(), second=new Resource();
        var a=new LodMeshResidency.Key(KEY,1);
        var b=new LodMeshResidency.Key(KEY,2);
        try(var cache=new LodMeshResidency<Resource>(100)) {
            check(cache.upload(a,80,()->first),"resident upload admitted");
            check(cache.use(a,1)==first,"mesh borrows current resource");
            check(!cache.upload(b,80,()->{throw new AssertionError("allocated beyond budget");}),"in-flight bytes remain charged");
            check(cache.chargedBytes()==80 && cache.retiredCount()==1 && first.closed==0,"deferred retirement preserves accounting");
            cache.beginFrame(1);
            check(first.closed==1 && cache.chargedBytes()==0,"completed use releases exactly once");
            check(cache.upload(b,80,()->second),"replacement admitted after completion");
            cache.use(b,2);
            cache.invalidate(key->true);
            check(cache.use(b,3)==null && cache.chargedBytes()==80,"invalidated surface loses ownership immediately");
            cache.setBudget(40);
            check(!cache.upload(a,30,Resource::new),"budget reduction waits without allocating");
            cache.beginFrame(2);
            check(second.closed==1 && cache.upload(a,30,Resource::new),"budget reduction recovers after completion");
        }
        check(first.closed==1 && second.closed==1,"no duplicate retirement at shutdown");
    }
}
