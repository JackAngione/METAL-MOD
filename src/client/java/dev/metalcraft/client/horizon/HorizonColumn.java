package dev.metalcraft.client.horizon;

/** Immutable sampled column envelope. No chunk, block palette, level or light-engine owner survives here. */
public record HorizonColumn(int x, int z, int sampleSize, Cell[] cells) {
    public record Material(float u, float v, int color, int light, boolean water, boolean translucent) { }
    public record Surface(float low, float high, Material material) {
        public Surface {
            if (!Float.isFinite(low) || !Float.isFinite(high) || low >= high) throw new IllegalArgumentException("Envelope height");
            java.util.Objects.requireNonNull(material);
        }
    }
    public record Cell(Surface solid, Surface fluid) { }
    @FunctionalInterface public interface Output {
        void face(float[] positions, Material material, int axis, int sign);
    }
    public HorizonColumn {
        if (sampleSize != 1 && sampleSize != 2 && sampleSize != 4) throw new IllegalArgumentException("Column sample size");
        if (cells.length != 256 / (sampleSize * sampleSize)) throw new IllegalArgumentException("Column sample count");
        cells = cells.clone();
        for (var cell : cells) java.util.Objects.requireNonNull(cell);
    }
    @Override public Cell[] cells() { return cells.clone(); }

    /** Immutable aggregate; avoids cloning the column's sample array for group meshing. */
    Surface surface(int x,int z,int cellSize,boolean fluid) {
        int stride=cellSize/sampleSize,width=16/sampleSize;
        if(stride==1) { var cell=cells[x+z*width]; return fluid?cell.fluid:cell.solid; }
        float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY; Material material=null;
        for(int dz=0;dz<stride;dz++) for(int dx=0;dx<stride;dx++) {
            var cell=cells[x*stride+dx+(z*stride+dz)*width]; var value=fluid?cell.fluid:cell.solid;
            if(value==null) continue;
            low=Math.min(low,value.low);
            if(value.high>high) { high=value.high;material=value.material; }
        }
        return material==null?null:new Surface(low,high,material);
    }

    /** Closed envelopes survive independent neighbor residency and unlike neighboring LOD tiers. */
    public int emit(int cellSize, Output output) {
        if (cellSize < sampleSize || cellSize > 16 || (cellSize & (cellSize-1)) != 0) throw new IllegalArgumentException("Horizon cell size");
        int width = 16 / cellSize, stride = cellSize / sampleSize, sampleWidth = 16 / sampleSize;
        int[] count = {0}; float[] positions = new float[12];
        for (boolean fluid : new boolean[]{false, true}) {
            Surface[] grid = new Surface[width * width];
            for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                Material material = null;
                for (int dz = 0; dz < stride; dz++) for (int dx = 0; dx < stride; dx++) {
                    Cell cell = cells[x * stride + dx + (z * stride + dz) * sampleWidth];
                    Surface surface = fluid ? cell.fluid : cell.solid;
                    if (surface == null) continue;
                    low = Math.min(low, surface.low);
                    if (surface.high > high) { high = surface.high; material = surface.material; }
                }
                if (material != null) grid[x + z * width] = new Surface(low, high, material);
            }
            for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                Surface surface = grid[x + z * width]; if (surface == null) continue;
                float x0 = x * cellSize, x1 = x0 + cellSize, z0 = z * cellSize, z1 = z0 + cellSize;
                emit(output, positions, count, surface.material, 1, 1, surface.high, z0, x0, z1, x1);
                emit(output, positions, count, surface.material, 1, -1, surface.low, z0, x0, z1, x1);
                for (int side = 0; side < 4; side++) {
                    int nx = x + (side == 0 ? -1 : side == 1 ? 1 : 0);
                    int nz = z + (side == 2 ? -1 : side == 3 ? 1 : 0);
                    Surface neighbor = nx < 0 || nz < 0 || nx >= width || nz >= width ? null : grid[nx + nz * width];
                    // Unlike fluid materials must not remove each other's boundary.
                    if (neighbor != null && fluid && neighbor.material.water != surface.material.water) neighbor = null;
                    if (neighbor == null || neighbor.high <= surface.low || neighbor.low >= surface.high)
                        wall(output, positions, count, surface.material, side, x0, x1, z0, z1, surface.low, surface.high);
                    else {
                        if (surface.low < neighbor.low) wall(output, positions, count, surface.material, side, x0, x1, z0, z1, surface.low, neighbor.low);
                        if (surface.high > neighbor.high) wall(output, positions, count, surface.material, side, x0, x1, z0, z1, neighbor.high, surface.high);
                    }
                }
            }
        }
        return count[0];
    }
    private static void wall(Output out, float[] p, int[] count, Material mat, int side,
                             float x0, float x1, float z0, float z1, float low, float high) {
        if (side < 2) emit(out,p,count,mat,0,side==0?-1:1,side==0?x0:x1,low,z0,high,z1);
        else emit(out,p,count,mat,2,side==2?-1:1,side==2?z0:z1,x0,low,x1,high);
    }
    private static void emit(Output out,float[] p,int[] count,Material mat,int axis,int sign,
                             float plane,float u0,float v0,float u1,float v1) {
        for (int side=0;side<(mat.water?2:1);side++) {
            int winding = side==0?sign:-sign;
            for (int i=0;i<4;i++) {
                int corner=winding>0?i:3-i;
                float u=corner==1||corner==2?u1:u0, v=corner>=2?v1:v0;
                p[i*3]=axis==0?plane:axis==1?v:u;
                p[i*3+1]=axis==0?u:axis==1?plane:v;
                p[i*3+2]=axis==0?v:axis==1?u:plane;
            }
            out.face(p,mat,axis,winding); count[0]++;
        }
    }
}
