package dev.metalcraft.client.lod;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Coalesces UI changes; the renderer adopts exactly one immutable value at a frame boundary. */
public final class LodFrameSettings {
    private final AtomicReference<LodSettings> pending = new AtomicReference<>(LodSettings.defaults());
    private LodSettings current = LodSettings.defaults();
    private LodSettings adopted;
    private LodCapabilities capabilities;

    public void request(LodSettings value) { pending.set(Objects.requireNonNull(value)); }

    public LodSettings beginFrame(LodCapabilities capabilities) {
        LodSettings desired = pending.get();
        if (desired != adopted || !capabilities.equals(this.capabilities)) {
            current = capabilities.effective(desired);
            adopted = desired;
            this.capabilities = capabilities;
        }
        return current;
    }

    public LodSettings current() { return current; }
}
