package dev.metalcraft.client.shader.sky;

import dev.metalcraft.client.shader.SceneColor;
import java.nio.ByteBuffer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Extracted world state only; 160-byte ABI shared with McSkyFrame. */
public record SkyFrameInputs(Matrix4f clipToWorld, Vector4f skyColor, Vector4f sunRain,
                             Vector4f cloudOrigin, Vector4f cloudSettings, Vector4f fogColor,
                             Vector4f moonPhase) {
    public static final int UNIFORM_BYTES = 160;
    // Includes the lowest noise octave's 256-cell period (world / 256 / 4).
    private static final double WORLD_PERIOD = 262144.0;

    public static SkyFrameInputs create(CameraRenderState camera, LevelRenderState level,
                                        CloudStatus clouds, float partialTick, Matrix4fc rasterProjection) {
        var sky = level.skyRenderState;
        Matrix4f clipToWorld = new Matrix4f(rasterProjection).mul(camera.viewRotationMatrix).invert();
        if (!clipToWorld.isFinite() || !Float.isFinite(sky.sunAngle) || !Float.isFinite(sky.moonAngle)) {
            throw new IllegalArgumentException("Non-finite sky camera or celestial angle");
        }
        double seconds = level.gameTime / 20.0 + partialTick / 20.0;
        float cloudMode = clouds == CloudStatus.OFF ? 0 : clouds == CloudStatus.FAST ? 1 : 2;
        float cloudAlpha = ((level.cloudColor >>> 24) & 255) / 255.0F;
        return new SkyFrameInputs(clipToWorld, new Vector4f(
            SceneColor.srgbToLinear(((sky.skyColor >>> 16) & 255) / 255.0F),
            SceneColor.srgbToLinear(((sky.skyColor >>> 8) & 255) / 255.0F),
            SceneColor.srgbToLinear((sky.skyColor & 255) / 255.0F), 1),
            new Vector4f(-(float)Math.sin(sky.sunAngle), (float)Math.cos(sky.sunAngle), 0,
                Math.clamp(sky.rainBrightness, 0, 1)),
            new Vector4f(wrap(camera.pos.x + seconds * 1.2), (float)camera.pos.y,
                wrap(camera.pos.z + seconds * 0.4), level.cloudHeight),
            new Vector4f(cloudMode, cloudAlpha, 0, 0), SceneColor.decodeRgb(camera.fogData.color),
            new Vector4f(-(float)Math.sin(sky.moonAngle), (float)Math.cos(sky.moonAngle), 0,
                sky.moonPhase.index()));
    }

    private static float wrap(double value) {
        return (float)(value - Math.floor(value / WORLD_PERIOD) * WORLD_PERIOD);
    }

    public boolean hasClouds() {
        return this.cloudSettings.x > 0 && this.cloudSettings.y > 0 && Float.isFinite(this.cloudOrigin.w);
    }

    public void write(ByteBuffer bytes) {
        this.clipToWorld.get(0, bytes);
        this.skyColor.get(64, bytes);
        this.sunRain.get(80, bytes);
        this.cloudOrigin.get(96, bytes);
        this.cloudSettings.get(112, bytes);
        this.fogColor.get(128, bytes);
        this.moonPhase.get(144, bytes);
    }
}
