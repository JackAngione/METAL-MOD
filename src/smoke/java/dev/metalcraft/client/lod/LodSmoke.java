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
        System.out.println("LOD smoke passed: settings recovery/round trip/gates, exact surface coverage, caves/overhangs/materials/seams, selection and stale/bounded jobs");
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
        check(!frames.beginFrame(LodCapabilities.current(true)).enabled(), "unimplemented geometry gated");
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
