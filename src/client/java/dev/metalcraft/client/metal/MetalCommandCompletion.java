package dev.metalcraft.client.metal;

/** Independent ownership of a submitted native command buffer for asynchronous readbacks. */
final class MetalCommandCompletion implements AutoCloseable {
    private long handle;

    MetalCommandCompletion(long handle) {
        if (handle == 0) throw new IllegalStateException("Metal did not create a completion ticket");
        this.handle = handle;
    }

    boolean completed(boolean wait) {
        if (this.handle == 0) throw new IllegalStateException("Completion ticket is closed");
        return MetalNative.nPollCommandCompletion(this.handle, wait);
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            MetalNative.nReleaseCommandCompletion(this.handle);
            this.handle = 0;
        }
    }
}
