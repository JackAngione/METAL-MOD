package dev.metalcraft.client.chunk;

public interface LightSnapshotAccess {
    LightSectionSnapshots metalcraft$lightSnapshots();
    void metalcraft$lightSnapshots(LightSectionSnapshots snapshots);
    /** Only the two audited native map owners opt in; other subclasses keep native storage. */
    interface Enabled { }

    static void copy(Object source, Object destination) {
        var snapshots = ((LightSnapshotAccess)source).metalcraft$lightSnapshots();
        if (snapshots != null) ((LightSnapshotAccess)destination).metalcraft$lightSnapshots(snapshots.copy());
    }
}
