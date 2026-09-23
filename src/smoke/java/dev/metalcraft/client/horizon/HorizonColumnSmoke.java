package dev.metalcraft.client.horizon;

import java.util.Arrays;
import java.util.Random;

/** Independent geometric invariants for the detached, whole-column proxy. */
public final class HorizonColumnSmoke {
    private static final HorizonColumn.Material ROCK=new HorizonColumn.Material(.1f,.2f,-1,0,false,false);
    private static final HorizonColumn.Material WATER=new HorizonColumn.Material(.3f,.4f,-1,0,true,true);
    public static void run() {
        generationBudget();
        mergedGeometry();
        var cells=new HorizonColumn.Cell[16];
        Arrays.fill(cells,new HorizonColumn.Cell(new HorizonColumn.Surface(-64,80,ROCK),new HorizonColumn.Surface(80,82.875f,WATER)));
        var column=new HorizonColumn(-17,33,4,cells);
        cells[0]=new HorizonColumn.Cell(null,null);
        check(column.cells()[0].solid()!=null,"constructor isolates array");
        var copy=column.cells(); copy[1]=null;
        check(column.cells()[1]!=null,"accessor isolates array");
        check(column.emit(16,(p,m,a,s)->{})==18,"whole column is one six-face solid plus two-sided water shell");
        check(column.emit(4,(p,m,a,s)->{})==144,"adjacent equal columns remove internal walls");
        var random=new Random(82574);
        for(int fixture=0;fixture<200;fixture++) {
            for(int i=0;i<16;i++) {
                float floor=-64+random.nextInt(140),top=floor+1+random.nextInt(80);
                cells[i]=new HorizonColumn.Cell(random.nextBoolean()?new HorizonColumn.Surface(floor,top,ROCK):null,
                        random.nextBoolean()?new HorizonColumn.Surface(top,top+random.nextInt(10)+.875f,WATER):null);
            }
            var shape=new HorizonColumn(-1,-1,4,cells);
            for(int size:new int[]{4,8,16}) {
                int faces=shape.emit(size,(p,m,axis,sign)->{
                    float ax=p[3]-p[0],ay=p[4]-p[1],az=p[5]-p[2];
                    float bx=p[6]-p[0],by=p[7]-p[1],bz=p[8]-p[2];
                    float[] normal={ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx};
                    check(normal[axis]*sign>0,"nondegenerate outward winding including underside");
                    for(int v=0;v<4;v++) {
                        check(Float.isFinite(p[v*3+1]),"finite height");
                        check(p[v*3]>=0 && p[v*3]<=16 && p[v*3+2]>=0 && p[v*3+2]<=16,"closed column bounds");
                    }
                });
                check(faces<=480,"bounded geometry independent of world height or block complexity");
            }
        }
        for(int sampleSize:new int[]{2,1}) {
            int width=16/sampleSize;
            var fine=new HorizonColumn.Cell[width*width];
            Arrays.fill(fine,new HorizonColumn.Cell(new HorizonColumn.Surface(-64,80,ROCK),
                    new HorizonColumn.Surface(80,82.875f,WATER)));
            var shape=new HorizonColumn(0,0,sampleSize,fine);
            check(shape.emit(sampleSize,(p,m,a,s)->{})==6*width*width+12*width,
                    "fine top/bottom faces and exposed walls");
            check(shape.emit(16,(p,m,a,s)->{})==18,"fine samples aggregate to closed coarse envelope");
        }
        check(HorizonDetail.sampleSize(1)==4 && HorizonDetail.sampleSize(4)==2 && HorizonDetail.sampleSize(5)==1,
                "sampling tiers");
        for(int level=1;level<=5;level++) {
            int expected=switch(level) {case 1->16;case 2->8;case 3->4;case 4->2;default->1;};
            check(HorizonDetail.cellSize(level,16)==expected,"detail cap " + level);
            check(dev.metalcraft.client.NativeLodSettingsCodec.readHorizonDetail(
                    com.google.gson.JsonParser.parseString(Integer.toString(level)))==level,"saved detail " + level);
        }
        check(dev.metalcraft.client.NativeLodSettingsCodec.readHorizonDetail(null)==1,"old config defaults to current detail");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readHorizonDetail(com.google.gson.JsonParser.parseString("0"))==1,
                "saved low detail clamps");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readHorizonDetail(com.google.gson.JsonParser.parseString("100"))==5,
                "saved high detail clamps");
        check(dev.metalcraft.client.NativeLodSettingsCodec.readHorizonDetail(com.google.gson.JsonParser.parseString("2.5"))==1,
                "fractional detail falls back");
        System.out.println("Compact horizon smoke passed: immutable ownership, 600 random tier fixtures, fine cells and tier mapping");
    }
    private static void generationBudget() {
        check(HorizonGpuBudget.bytes(0)==256L<<20,"unknown GPU budget retains conservative fallback");
        check(HorizonGpuBudget.bytes(4L<<30)==256L<<20,"small Metal working set is not overcommitted");
        check(HorizonGpuBudget.bytes(48L<<30)==1024L<<20,"large Apple Silicon supports bounded maximum-detail residency");
        check(HorizonGpuBudget.bytes(Long.MAX_VALUE)==1024L<<20,"GPU budget has an absolute cap");
        check(HorizonGenerationBudget.jobLimit(16,4_000_000L,0)==64,"idle many-core server keeps more generation in flight");
        check(HorizonGenerationBudget.jobLimit(4,4_000_000L,0)==16,"small CPU retains original ownership bound");
        check(HorizonGenerationBudget.jobLimit(16,35_000_000L,0)==16,"busy server reduces discovery window");
        check(HorizonGenerationBudget.jobLimit(16,45_000_000L,0)==0,"overloaded server pauses new generation");
        check(HorizonGenerationBudget.jobLimit(16,4_000_000L,48)==0,"slow client stops new generation");
        for(int cores:new int[]{1,4,8,16,64,Integer.MAX_VALUE}) for(int queued=0;queued<=64;queued++) {
            int limit=HorizonGenerationBudget.jobLimit(cores,1_000_000L,queued);
            check(limit>=0 && limit<=64 && limit+queued<=64,"bounded ready plus admitted ownership");
        }
        // Repeated ticks with no completions ramp up to capacity; pressure does not
        // evict work, and draining the client queue permits generation to resume.
        int jobs=0;
        for(int tick=0;tick<10;tick++) jobs+=Math.min(HorizonGenerationBudget.STARTS_PER_TICK,
                Math.max(0,HorizonGenerationBudget.jobLimit(16,1_000_000L,0)-jobs));
        check(jobs==64,"bounded ramp-up fills but cannot overflow window");
        check(HorizonGenerationBudget.jobLimit(16,1_000_000L,0)>HorizonGenerationBudget.jobLimit(16,1_000_000L,48),
                "discovery resumes after queue pressure clears");
    }
    private static void mergedGeometry() {
        var random=new Random(125981);
        for(int sample:new int[]{1,2,4}) for(int fixture=0;fixture<12;fixture++) {
            var cells=new HorizonColumn.Cell[256/(sample*sample)];
            for(int i=0;i<cells.length;i++) {
                int floor=random.nextInt(3),top=4+random.nextInt(3);
                var material=new HorizonColumn.Material(.1f,.2f,random.nextBoolean()?-1:0xffaaccee,random.nextInt(2),false,false);
                cells[i]=new HorizonColumn.Cell(random.nextInt(5)==0?null:new HorizonColumn.Surface(floor,top,material),
                        random.nextInt(3)==0?new HorizonColumn.Surface(top,top+1,WATER):null);
            }
            var column=new HorizonColumn(0,0,sample,cells);
            var group=new HorizonColumn[64];group[0]=column;
            for(int cell=sample;cell<=16;cell*=2) {
                var expected=new java.util.HashMap<String,Integer>();var actual=new java.util.HashMap<String,Integer>();
                column.emit(cell,(p,m,a,s)->raster(expected,p,m,a,s));
                HorizonMesher.emit(group,1,cell,(p,m,a,s)->raster(actual,p,m,a,s));
                check(expected.equals(actual),"merged quads preserve oriented area, all material attributes, gaps and water backs at cell "+cell);
            }
        }
        var flat=new HorizonColumn.Cell[256];
        Arrays.fill(flat,new HorizonColumn.Cell(new HorizonColumn.Surface(-64,80,ROCK),new HorizonColumn.Surface(80,82.875f,WATER)));
        var group=new HorizonColumn[64];
        for(int i=0;i<64;i++) group[i]=new HorizonColumn(i%8,i/8,1,flat);
        int[] faces={0};double[] area={0};
        var metadata=new dev.metalcraft.client.shader.water.WaterVertexMetadata.Builder();
        var normals=new java.util.ArrayList<int[]>();
        HorizonMesher.emit(group,-1L,1,(p,m,a,s)-> {
            faces[0]++;
            if(m.water()) { metadata.putQuad(normals.size()*4,p,0,0,0);normals.add(new int[]{a,s}); }
            if(!m.water()) {
                float plane=p[a];check(a==1 || plane==0 || plane==128,"covered inter-column walls removed");
                if(a==1 && s==1) area[0]+=rectangleArea(p,a);
            }
        });
        check(area[0]==128*128,"group top covers every one-block sample exactly once");
        check(faces[0]==326,"flat maximum-detail group collapses to six solid and 320 bounded two-sided fluid quads");
        var waterMetadata=metadata.build(normals.size()*4);
        for(int i=0;i<normals.size();i++) for(int v=0;v<4;v++) {
            int vertex=i*4+v;var n=normals.get(i);
            check(waterMetadata.materialId(vertex)==1,"merged face retains water identity");
            float normal=n[0]==0?waterMetadata.normalX(vertex):n[0]==1?waterMetadata.normalY(vertex):waterMetadata.normalZ(vertex);
            check(Math.abs(normal-n[1])<.0001f,"merged water keeps both outward and inward face normals");
        }
        // A native-owned/missing neighbor must keep a sealed boundary.
        boolean[] boundary={false};
        HorizonMesher.emit(group,2L,1,(p,m,a,s)-> { if(a==0 && p[0]==16 && s==-1 && !m.water()) boundary[0]=true; });
        check(boundary[0],"mask holes retain closed native/residency handoff walls");
        // Compare a two-column union with the old emitter, removing only portions
        // covered by the adjacent envelope. This is independent of greedy merging.
        for(int fixture=0;fixture<20;fixture++) {
            var pair=new HorizonColumn[64];
            var input=new HorizonColumn.Cell[2][16];
            for(int c=0;c<2;c++) {
                for(int i=0;i<16;i++) {
                    int low=random.nextInt(3),high=4+random.nextInt(3);
                    input[c][i]=new HorizonColumn.Cell(random.nextInt(4)==0?null:new HorizonColumn.Surface(low,high,ROCK),
                            random.nextInt(3)==0?new HorizonColumn.Surface(high,high+1,WATER):null);
                }
                pair[c]=new HorizonColumn(c,0,4,input[c]);
            }
            var expected=new java.util.HashMap<String,Integer>();var actual=new java.util.HashMap<String,Integer>();
            for(int c=0;c<2;c++) {
                final int column=c;
                pair[c].emit(4,(p,m,a,s)-> {
                    var shifted=p.clone();for(int v=0;v<4;v++) shifted[v*3]+=column*16;
                    raster(expected,shifted,m,a,s,(u,v)-> {
                        if(a!=0 || shifted[0]!=16) return true;
                        var neighbor=input[1-column][(column==0?0:3)+(int)(v/4)*4];
                        var cover=m.water()?neighbor.fluid():neighbor.solid();
                        return cover==null || u<cover.low() || u>=cover.high();
                    });
                });
            }
            HorizonMesher.emit(pair,3,4,(p,m,a,s)->raster(actual,p,m,a,s));
            check(expected.equals(actual),"group clipping preserves randomized exposed material and water boundaries");
        }
    }
    private static double rectangleArea(float[] p,int axis) {
        int u=(axis+1)%3,v=(axis+2)%3;
        float minU=Float.POSITIVE_INFINITY,maxU=Float.NEGATIVE_INFINITY,minV=minU,maxV=maxU;
        for(int i=0;i<4;i++) { minU=Math.min(minU,p[i*3+u]);maxU=Math.max(maxU,p[i*3+u]);minV=Math.min(minV,p[i*3+v]);maxV=Math.max(maxV,p[i*3+v]); }
        return (maxU-minU)*(maxV-minV);
    }
    private static void raster(java.util.Map<String,Integer> result,float[] p,HorizonColumn.Material material,int axis,int sign) {
        raster(result,p,material,axis,sign,(u,v)->true);
    }
    private static void raster(java.util.Map<String,Integer> result,float[] p,HorizonColumn.Material material,int axis,int sign,
                               java.util.function.BiPredicate<Float,Float> include) {
        int u=(axis+1)%3,v=(axis+2)%3;
        float minU=Float.POSITIVE_INFINITY,maxU=Float.NEGATIVE_INFINITY,minV=minU,maxV=maxU;
        for(int i=0;i<4;i++) { minU=Math.min(minU,p[i*3+u]);maxU=Math.max(maxU,p[i*3+u]);minV=Math.min(minV,p[i*3+v]);maxV=Math.max(maxV,p[i*3+v]); }
        float ax=p[3]-p[0],ay=p[4]-p[1],az=p[5]-p[2],bx=p[6]-p[0],by=p[7]-p[1],bz=p[8]-p[2];
        float[] normal={ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx};
        check(normal[axis]*sign>0,"merged face winding");
        for(float y=minU+.5f;y<maxU;y++) for(float x=minV+.5f;x<maxV;x++)
            if(include.test(y,x)) result.merge(axis+":"+sign+":"+p[axis]+":"+y+":"+x+":"+material,1,Integer::sum);
    }
    private static void check(boolean pass,String message) { if(!pass) throw new AssertionError(message); }
}
