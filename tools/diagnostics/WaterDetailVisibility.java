import javax.imageio.ImageIO;
import java.io.File;

/** Final-image detail check: reject changes confined to broad shading or a few pixels. */
class WaterDetailVisibility {
    public static void main(String[] args) throws Exception {
        var before = ImageIO.read(new File(args[0]));
        var after = ImageIO.read(new File(args[1]));
        int w = before.getWidth(), h = before.getHeight();
        if (w != after.getWidth() || h != after.getHeight()) throw new AssertionError("Mismatched captures");
        double[] delta = new double[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int a = before.getRGB(x, y), b = after.getRGB(x, y);
            delta[y*w+x] = (0.2126 * (((b>>16)&255)-((a>>16)&255))
                + 0.7152 * (((b>>8)&255)-((a>>8)&255)) + 0.0722 * ((b&255)-(a&255)));
        }
        double energy = 0, raw = 0; int n = 0, changed = 0;
        // Near-water interior; exclude shore, dry controls, sky and the frame boundary.
        for (int y = (int)(h*.40); y < (int)(h*.90); y++) for (int x = (int)(w*.15); x < (int)(w*.85); x++) {
            double mean = 0;
            for (int dy=-2; dy<=2; dy++) for (int dx=-2; dx<=2; dx++) mean += delta[(y+dy)*w+x+dx];
            double d = delta[y*w+x], detail = d - mean/25;
            energy += detail*detail; raw += d*d; n++;
            if (Math.abs(d) >= 5) changed++;
        }
        double rms = Math.sqrt(energy/n), coverage = 100.0*changed/n;
        System.out.printf("Final water: fine-detail RMS %.3f/255, total RMS %.3f/255, >=5/255 coverage %.2f%%%n", rms, Math.sqrt(raw/n), coverage);
        // Fine texture must be discernible over a substantial near-water region, not merely nonzero.
        if (rms < 2.5 || coverage < 10) throw new AssertionError("Fine water detail is visually too weak");
    }
}
