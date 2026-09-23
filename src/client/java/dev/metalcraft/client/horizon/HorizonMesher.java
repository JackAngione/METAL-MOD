package dev.metalcraft.client.horizon;

import dev.metalcraft.client.horizon.HorizonColumn.Material;
import dev.metalcraft.client.horizon.HorizonColumn.Surface;

/** Exact envelope union within one resident group. Only equal planar attributes merge. */
public final class HorizonMesher {
    private HorizonMesher() { }

    public static void emit(HorizonColumn[] columns,long mask,int cellSize,HorizonColumn.Output output) {
        if(columns.length!=64 || cellSize<1 || cellSize>16 || (cellSize&(cellSize-1))!=0)
            throw new IllegalArgumentException("Horizon group geometry");
        int width=128/cellSize, columnWidth=16/cellSize;
        Surface[] grid=new Surface[width*width];
        float[] positions=new float[12];
        for(boolean fluid:new boolean[]{false,true}) {
            java.util.Arrays.fill(grid,null);
            for(int slot=0;slot<64;slot++) {
                if((mask&(1L<<slot))==0 || columns[slot]==null) continue;
                var column=columns[slot];
                if(cellSize<column.sampleSize()) throw new IllegalArgumentException("Horizon sample resolution");
                int ox=(slot%8)*columnWidth,oz=(slot/8)*columnWidth;
                for(int z=0;z<columnWidth;z++) for(int x=0;x<columnWidth;x++)
                    grid[ox+x+(oz+z)*width]=column.surface(x,z,cellSize,fluid);
            }
            // Bound merged water quads to the existing maximum 16-block primitive
            // size, retaining local transparency sorting and two-sided metadata.
            int mergeLimit=fluid?16/cellSize:width;
            for(int sign:new int[]{-1,1}) {
                boolean[] used=new boolean[grid.length];
                for(int z=0;z<width;z++) for(int x=0;x<width;x++) {
                    int index=x+z*width; var surface=grid[index];
                    if(surface==null || used[index]) continue;
                    int w=1,h=1;
                    while(w<mergeLimit && x+w<width && !used[index+w] && samePlane(surface,grid[index+w],sign)) w++;
                    rows: while(h<mergeLimit && z+h<width) {
                        for(int dx=0;dx<w;dx++) if(used[index+dx+h*width] || !samePlane(surface,grid[index+dx+h*width],sign)) break rows;
                        h++;
                    }
                    for(int dz=0;dz<h;dz++) java.util.Arrays.fill(used,index+dz*width,index+dz*width+w,true);
                    face(output,positions,surface.material(),1,sign,sign>0?surface.high():surface.low(),
                            z*cellSize,x*cellSize,(z+h)*cellSize,(x+w)*cellSize);
                }
            }
            // Two exposed vertical intervals at most. Merge runs only when both
            // endpoints and every shaded material attribute are identical.
            for(int side=0;side<4;side++) for(int slice=0;slice<width;slice++) for(int part=0;part<2;part++) {
                int run=0; Material material=null; float low=0,high=0;
                for(int along=0;along<=width;along++) {
                    Surface current=null,neighbor=null;
                    if(along<width) {
                        int x=side<2?slice:along,z=side<2?along:slice;
                        current=grid[x+z*width];
                        int nx=x+(side==0?-1:side==1?1:0),nz=z+(side==2?-1:side==3?1:0);
                        if(nx>=0 && nz>=0 && nx<width && nz<width) neighbor=grid[nx+nz*width];
                        if(current!=null && neighbor!=null && fluid && current.material().water()!=neighbor.material().water()) neighbor=null;
                    }
                    float nextLow=0,nextHigh=0; Material next=null;
                    if(current!=null) {
                        boolean overlaps=neighbor!=null && neighbor.high()>current.low() && neighbor.low()<current.high();
                        nextLow=part==0?current.low():overlaps?Math.max(current.low(),neighbor.high()):current.high();
                        nextHigh=part==0?(overlaps?Math.min(current.high(),neighbor.low()):current.high()):current.high();
                        if(nextLow<nextHigh) next=current.material();
                    }
                    if(material!=null && (along-run>=mergeLimit || next==null || !material.equals(next) || low!=nextLow || high!=nextHigh)) {
                        int sign=(side&1)==0?-1:1;
                        float plane=(slice+(sign>0?1:0))*cellSize;
                        if(side<2) face(output,positions,material,0,sign,plane,low,run*cellSize,high,along*cellSize);
                        else face(output,positions,material,2,sign,plane,run*cellSize,low,along*cellSize,high);
                        material=null;
                    }
                    if(material==null && next!=null) { run=along;material=next;low=nextLow;high=nextHigh; }
                }
            }
        }
    }

    private static boolean samePlane(Surface a,Surface b,int sign) {
        return b!=null && (sign>0?a.high()==b.high():a.low()==b.low()) && a.material().equals(b.material());
    }

    private static void face(HorizonColumn.Output output,float[] p,Material material,int axis,int sign,
                             float plane,float u0,float v0,float u1,float v1) {
        for(int side=0;side<(material.water()?2:1);side++) {
            int winding=side==0?sign:-sign;
            for(int vertex=0;vertex<4;vertex++) {
                int corner=winding>0?vertex:3-vertex;
                float u=corner==1||corner==2?u1:u0,v=corner>=2?v1:v0;
                p[vertex*3]=axis==0?plane:axis==1?v:u;
                p[vertex*3+1]=axis==0?u:axis==1?plane:v;
                p[vertex*3+2]=axis==0?v:axis==1?u:plane;
            }
            output.face(p,material,axis,winding);
        }
    }
}
