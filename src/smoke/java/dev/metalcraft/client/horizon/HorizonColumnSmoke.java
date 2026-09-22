package dev.metalcraft.client.horizon;

import java.util.Arrays;
import java.util.Random;

/** Independent geometric invariants for the detached, whole-column proxy. */
public final class HorizonColumnSmoke {
    private static final HorizonColumn.Material ROCK=new HorizonColumn.Material(.1f,.2f,-1,0,false,false);
    private static final HorizonColumn.Material WATER=new HorizonColumn.Material(.3f,.4f,-1,0,true,true);
    public static void run() {
        var cells=new HorizonColumn.Cell[16];
        Arrays.fill(cells,new HorizonColumn.Cell(new HorizonColumn.Surface(-64,80,ROCK),new HorizonColumn.Surface(80,82.875f,WATER)));
        var column=new HorizonColumn(-17,33,cells);
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
            var shape=new HorizonColumn(-1,-1,cells);
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
        System.out.println("Compact horizon smoke passed: immutable ownership, 600 random tier fixtures, closure/winding and geometry caps");
    }
    private static void check(boolean pass,String message) { if(!pass) throw new AssertionError(message); }
}
