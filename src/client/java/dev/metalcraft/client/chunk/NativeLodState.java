package dev.metalcraft.client.chunk;

/** The selected cell size travels with the native snapshot, result, and installed mesh. */
public interface NativeLodState {
    int metalcraft$cellSize();
    void metalcraft$cellSize(int size);
}
