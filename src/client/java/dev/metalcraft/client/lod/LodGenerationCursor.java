package dev.metalcraft.client.lod;

/** Constant-space outward rings. Selection never changes Minecraft's loaded square. */
public final class LodGenerationCursor {
    public record Column(int x, int z) { }
    private final int x, z, loaded, horizon;
    private int radius, offset;

    public LodGenerationCursor(int x, int z, int loaded, int horizon) {
        this.x=x; this.z=z; this.loaded=Math.max(0,loaded);
        this.horizon=Math.clamp(horizon,0,256); radius=this.loaded+1;
    }

    public Column next() {
        while(radius<=horizon) {
            int side=offset/(radius*2), along=offset%(radius*2);
            int dx=switch(side) { case 0 -> -radius+along; case 1 -> radius; case 2 -> radius-along; default -> -radius; };
            int dz=switch(side) { case 0 -> -radius; case 1 -> -radius+along; case 2 -> radius; default -> radius-along; };
            if(++offset==8*radius) { offset=0; radius++; }
            // Include any chunk intersecting the circular horizon, including edge chunks.
            long nearX=Math.max(0,Math.abs(dx)-1),nearZ=Math.max(0,Math.abs(dz)-1);
            if(nearX*nearX+nearZ*nearZ >= (long)horizon*horizon) continue;
            if(Math.abs((long)x+dx)>1_874_999 || Math.abs((long)z+dz)>1_874_999) continue;
            return new Column(x+dx,z+dz);
        }
        return null;
    }
}
