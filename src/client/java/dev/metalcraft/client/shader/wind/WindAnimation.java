package dev.metalcraft.client.shader.wind;

/** Shared foliage/shadow clock running at 80% speed without changing the deformation strength. */
public final class WindAnimation {
    // The shader repeats after 1024 animation seconds: at 0.8x, that is 1280 game seconds.
    public static final long PERIOD_TICKS = 25_600L;

    private WindAnimation() { }

    public static float seconds(long gameTicks, float partialTick) {
        if (!Float.isFinite(partialTick) || partialTick < 0 || partialTick > 1) {
            throw new IllegalArgumentException("Invalid wind partial tick");
        }
        // Reduce integer ticks before conversion to retain smooth movement in long-running worlds.
        // Use the full game clock: scaling water's already-wrapped 1024-second clock would jump.
        double ticks = Math.floorMod(gameTicks, PERIOD_TICKS) + (double)partialTick;
        return (float)((ticks / 25.0) % 1024.0);
    }
}
