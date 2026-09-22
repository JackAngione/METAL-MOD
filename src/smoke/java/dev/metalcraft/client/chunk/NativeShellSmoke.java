package dev.metalcraft.client.chunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Independent unit-face oracle for the proxy's exterior, including seams and cavities. */
final class NativeShellSmoke {
    private record Face(int axis, int sign, int plane, int u0, int v0, int u1, int v1) { }
    static void run() {
        fluidEnvelope();
        for (int tier : new int[]{2, 4, 8, 16}) {
            check(mesh((x,y,z) -> false, tier).isEmpty(), "empty stays empty");
            check(mesh((x,y,z) -> true, tier).isEmpty(), "buried sections have no shell geometry");
            var solid = mesh((x,y,z) -> inside(x,y,z), tier);
            var hollow = mesh((x,y,z) -> inside(x,y,z) && (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15), tier);
            check(solid.equals(hollow), "interior cavities do not add geometry");
            check(coverage(solid).size() == 6 * 256, "closed isolated outer skin");
            var joined = mesh((x,y,z) -> y >= 0 && y < 16 && z >= 0 && z < 16 && x >= 0 && x <= 16, tier);
            check(coverage(joined).size() == 5 * 256, "neighbor removes the shared boundary");
            // A partial neighboring face must retain the closure, including when its tier differs.
            var partial = mesh((x,y,z) -> inside(x,y,z) || x == 16 && y < 8 && y >= 0 && z >= 0 && z < 16, tier);
            check(coverage(partial).equals(coverage(solid)), "partial neighbor cannot open a shell seam");
            for (int seed = 0; seed < 80; seed++) {
                Random random = new Random(seed);
                boolean[][][] source = new boolean[16][16][16];
                for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
                    int lo=random.nextInt(16), hi=random.nextInt(17);
                    for (int y=lo; y<hi; y++) source[x][y][z]=random.nextBoolean();
                }
                var actual=mesh((x,y,z) -> inside(x,y,z) && source[x][y][z],tier);
                check(actual.size()<=160,"strict complexity bound");
                boolean[][][] proxy=new boolean[16][16][16];
                int cell=NativeShellMesher.grid(tier);
                for (int z=0;z<16;z+=cell) for (int x=0;x<16;x+=cell) {
                    int lo=16,hi=0;
                    for(int dz=0;dz<cell;dz++) for(int dx=0;dx<cell;dx++) for(int y=0;y<16;y++)
                        if(source[x+dx][y][z+dz]) { lo=Math.min(lo,y); hi=Math.max(hi,y+1); }
                    for(int dz=0;dz<cell;dz++) for(int dx=0;dx<cell;dx++) for(int y=lo;y<hi;y++) proxy[x+dx][y][z+dz]=true;
                }
                Set<String> expected=new HashSet<>();
                for(int z=0;z<16;z++) for(int y=0;y<16;y++) for(int x=0;x<16;x++) if(proxy[x][y][z]) {
                    int[] p={x,y,z};
                    for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
                        int[] n={x,y,z}; n[axis]+=sign;
                        if(!inside(n[0],n[1],n[2]) || !proxy[n[0]][n[1]][n[2]])
                            expected.add(key(axis,sign,p[axis]+(sign>0?1:0),p[(axis+1)%3],p[(axis+2)%3]));
                    }
                }
                check(expected.equals(coverage(actual)),"all and only outward shell faces, seed="+seed+" tier="+tier);
            }
        }
        check(mesh((x,y,z) -> inside(x,y,z),16).size()==6,"extreme solid tile is one six-face model");
        System.out.println("Shell smoke passed: 320 exterior-oracle cases, <=160 quads, cavities, empty/buried sections, neighbor seams and source materials");
    }
    private static void fluidEnvelope() {
        for (int tier : new int[]{2, 4, 8, 16}) {
            var metadata = new dev.metalcraft.client.shader.water.WaterVertexMetadata.Builder();
            int[] vertex = {0};
            float height = 8f / 9f;
            int quads = NativeFluidShellMesher.build((x,y,z) -> y >= -1 && y < 8,
                    (x,y,z) -> y == 7 ? height : 1, tier, true, (p,axis,sign,sx,sy,sz) -> {
                check(axis == 1, "ocean only emits its exposed cap");
                for (int i=0;i<4;i++) check(Math.abs(p[i*3+1]-(7+height))<1e-6, "fractional source-water surface");
                metadata.putQuad(vertex[0], p, .25f, 0, -.5f);
                vertex[0] += 4;
            });
            check(quads == 2 * (16/NativeShellMesher.grid(tier)) * (16/NativeShellMesher.grid(tier)), "bounded double-sided ocean cap");
            var water = metadata.build(vertex[0]);
            for (int i=0;i<vertex[0];i++) {
                check(water.materialId(i)==1 && water.normalX(i)==0 && water.normalZ(i)==0, "explicit water identity and planar normal");
                check(water.normalY(i)==((i/4)%2==0?1:-1), "opposite underside winding");
                check(water.flowX(i)==.25f && water.flowZ(i)==-.5f, "flow sidecar follows reduced vertices");
            }
            check(NativeFluidShellMesher.build((x,y,z)->true,(x,y,z)->1,tier,true,(p,a,d,x,y,z)->{
                throw new AssertionError("buried fluid should not emit");
            })==0,"buried water empty");
            var positions = new ArrayList<float[]>();
            int faces = NativeFluidShellMesher.build((x,y,z)->inside(x,y,z) && y==0,
                    (x,y,z)->height,tier,false,(p,a,d,x,y,z)->positions.add(p.clone()));
            check(faces<=160, "lava obeys the solid shell bound");
            for(var p:positions) for(int i=0;i<4;i++) check(p[i*3+1]>=0 && p[i*3+1]<=height,"thin fluid walls meet fractional caps");
        }
        boolean[] step = {false};
        NativeFluidShellMesher.build((x,y,z)->inside(x,y,z) && y==0,
                (x,y,z)->x<8?.5f:.9f,8,false,(p,axis,sign,x,y,z)-> {
                    if(axis==0 && p[0]==8 && sign==-1) {
                        float low=16,high=0;
                        for(int i=0;i<4;i++) { low=Math.min(low,p[i*3+1]); high=Math.max(high,p[i*3+1]); }
                        check(low==.5f && high==.9f,"fractional flow step closes its vertical gap"); step[0]=true;
                    }
                });
        check(step[0],"different flow heights have a connecting wall");
        System.out.println("Fluid shells passed: ocean caps/undersides, fractional heights, closed thin pools, buried water, material/flow metadata and bounds");
    }
    private static boolean inside(int x,int y,int z) { return x>=0 && y>=0 && z>=0 && x<16 && y<16 && z<16; }
    private static List<Face> mesh(NativeShellMesher.Occupancy source,int tier) {
        var result=new ArrayList<Face>();
        int count=NativeShellMesher.build(source,tier,(axis,sign,plane,u0,v0,u1,v1,sx,sy,sz)-> {
            check(inside(sx,sy,sz) && source.occupied(sx,sy,sz),"material sample belongs to occupied input");
            check(plane>=0 && plane<=16 && u0>=0 && v0>=0 && u1<=16 && v1<=16 && u1>u0 && v1>v0,"finite section bounds");
            result.add(new Face(axis,sign,plane,u0,v0,u1,v1));
        });
        check(count==result.size(),"actual emitted count");
        return result;
    }
    private static String key(int axis,int sign,int plane,int u,int v) { return axis+":"+sign+":"+plane+":"+u+":"+v; }
    private static Set<String> coverage(List<Face> faces) {
        Set<String> result=new HashSet<>();
        for(var f:faces) for(int v=f.v0;v<f.v1;v++) for(int u=f.u0;u<f.u1;u++)
            check(result.add(key(f.axis,f.sign,f.plane,u,v)),"no duplicate face coverage");
        return result;
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
