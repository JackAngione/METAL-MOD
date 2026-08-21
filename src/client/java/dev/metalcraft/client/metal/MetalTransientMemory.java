package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Persistent unified-memory arenas rotated across submissions. */
final class MetalTransientMemory implements TransientMemory, AutoCloseable {
	private static final int FRAME_COUNT = 3;
	private static final long MEBIBYTE = 1024L * 1024L;
	private static final long MINIMUM_ARENA_SIZE = 16L * MEBIBYTE;
	private static final long MAXIMUM_ARENA_SIZE = 128L * MEBIBYTE;
	private static final int ARENA_USAGE = GpuBuffer.USAGE_MAP_WRITE
		| GpuBuffer.USAGE_COPY_SRC
		| GpuBuffer.USAGE_COPY_DST
		| GpuBuffer.USAGE_VERTEX
		| GpuBuffer.USAGE_INDEX
		| GpuBuffer.USAGE_UNIFORM
		| GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
		| GpuBuffer.USAGE_INDIRECT_PARAMETERS;

	private final MetalGpuDevice device;
	private final long initialArenaSize;
	private final FrameSlot[] frames = new FrameSlot[FRAME_COUNT];
	private int frameIndex;
	private FrameSlot activeFrame;
	private boolean closed;

	MetalTransientMemory(final MetalGpuDevice device, final MetalCommandEncoder commandEncoder) {
		this.device = device;
		this.initialArenaSize = initialArenaSize(device.metal().recommendedWorkingSetBytes());
		for (int index = 0; index < this.frames.length; index++) {
			this.frames[index] = new FrameSlot();
		}
	}

	@Override
	public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
		validateAlignment(alignment);
		return ByteBuffer.allocateDirect(toInt(allocationSize(size, minimumAllocation, elementSize))).order(ByteOrder.nativeOrder());
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(
		final long size,
		final long alignment,
		final @GpuBuffer.Usage int usage,
		final long minimumAllocation,
		final long elementSize
	) {
		return this.allocateMapped(size, alignment, allocationSize(size, minimumAllocation, elementSize));
	}

	@Override
	public GpuBufferSlice allocateGpu(
		final long size,
		final long alignment,
		final @GpuBuffer.Usage int usage,
		final long minimumAllocation,
		final long elementSize
	) {
		return this.allocateSlice(size, alignment, allocationSize(size, minimumAllocation, elementSize)).slice();
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(
		final long size,
		final long alignment,
		final @GpuBuffer.Usage int usage,
		final long minimumAllocation,
		final long elementSize
	) {
		return this.allocateMapped(size, alignment, allocationSize(size, minimumAllocation, elementSize));
	}

	@Override
	public GpuBufferSlice uploadStaging(
		final List<ByteBuffer> data,
		final long alignment,
		final @GpuBuffer.Usage int usage,
		final long minimumAllocation,
		final long elementSize
	) {
		return this.uploadShared(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(
		final List<ByteBuffer> data,
		final long alignment,
		final @GpuBuffer.Usage int usage,
		final long minimumAllocation,
		final long elementSize
	) {
		return this.uploadShared(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		List<GpuBufferSlice> result = new ArrayList<>(data.size());
		for (ByteBuffer source : data) result.add(this.uploadStaging(source, alignment, usage));
		return result;
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		List<GpuBufferSlice> result = new ArrayList<>(data.size());
		for (ByteBuffer source : data) result.add(this.uploadGpu(source, alignment, usage));
		return result;
	}

	/** Transfers ownership of a submitted command buffer when arena memory must remain in flight. */
	boolean finishSubmission(final MetalCommandBuffer commands, final boolean completed) {
		FrameSlot frame = this.activeFrame;
		this.activeFrame = null;
		if (frame == null || commands == null) return false;
		if (completed) {
			frame.reset();
			return false;
		}
		frame.inFlight = commands;
		return true;
	}

	void waitForAllFrames() {
		for (FrameSlot frame : this.frames) frame.retire();
	}

	int nativeAllocationCountForTesting() {
		int count = 0;
		for (FrameSlot frame : this.frames) count += frame.arenas.size();
		return count;
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.waitForAllFrames();
		for (FrameSlot frame : this.frames) frame.close();
		this.closed = true;
	}

	private GpuBufferSlice uploadShared(
		final List<ByteBuffer> data,
		final long alignment,
		final int usage,
		final long minimumAllocation,
		final long elementSize
	) {
		long size = totalSize(data);
		try (GpuBufferSlice.MappedView mapping = this.allocateGpuMapped(size, alignment, usage, minimumAllocation, elementSize)) {
			copy(data, mapping.data());
			return mapping.slice();
		}
	}

	private GpuBufferSlice.MappedView allocateMapped(final long size, final long alignment, final long reservedSize) {
		ArenaSlice allocation = this.allocateSlice(size, alignment, reservedSize);
		return new GpuBufferSlice.MappedView(allocation.slice(), allocation.bytes(), () -> { });
	}

	private ArenaSlice allocateSlice(final long size, final long alignment, final long reservedSize) {
		if (this.closed) throw new IllegalStateException("Metal transient memory is closed");
		validateAlignment(alignment);
		if (size > Integer.MAX_VALUE || reservedSize > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("A mapped transient allocation cannot exceed 2 GiB");
		}
		FrameSlot frame = this.beginFrame();
		ArenaSlice allocation = frame.allocate(size, reservedSize, alignment);
		if (allocation != null) return allocation;

		long required = Math.addExact(reservedSize, alignment - 1L);
		long capacity = Math.max(this.initialArenaSize, nextPowerOfTwo(required));
		Arena arena = new Arena(capacity);
		frame.arenas.add(arena);
		frame.arenaIndex = frame.arenas.size() - 1;
		return arena.allocate(size, reservedSize, alignment);
	}

	private FrameSlot beginFrame() {
		if (this.activeFrame != null) return this.activeFrame;
		FrameSlot frame = this.frames[this.frameIndex];
		frame.retire();
		frame.reset();
		this.frameIndex = (this.frameIndex + 1) % this.frames.length;
		this.activeFrame = frame;
		return frame;
	}

	private final class FrameSlot implements AutoCloseable {
		private final List<Arena> arenas = new ArrayList<>();
		private int arenaIndex;
		private MetalCommandBuffer inFlight;

		private ArenaSlice allocate(final long size, final long reservedSize, final long alignment) {
			for (int index = this.arenaIndex; index < this.arenas.size(); index++) {
				ArenaSlice allocation = this.arenas.get(index).allocate(size, reservedSize, alignment);
				if (allocation != null) {
					this.arenaIndex = index;
					return allocation;
				}
			}
			return null;
		}

		private void retire() {
			if (this.inFlight == null) return;
			this.inFlight.waitUntilCompleted();
			this.inFlight.close();
			this.inFlight = null;
		}

		private void reset() {
			this.arenaIndex = 0;
			for (Arena arena : this.arenas) arena.reset();
		}

		@Override
		public void close() {
			this.retire();
			for (Arena arena : this.arenas) arena.close();
			this.arenas.clear();
		}
	}

	private final class Arena implements AutoCloseable {
		private final MetalGpuBuffer buffer;
		private final MetalBuffer.Mapping mapping;
		private final ByteBuffer bytes;
		private final long capacity;
		private long cursor;

		private Arena(final long capacity) {
			this.capacity = capacity;
			MetalBuffer metal = MetalTransientMemory.this.device.metal().createBuffer(
				Math.addExact(capacity, 255L), MetalBuffer.StorageMode.SHARED
			);
			this.buffer = new MetalGpuBuffer(ARENA_USAGE, capacity, metal);
			this.mapping = metal.map(0L, capacity);
			this.bytes = this.mapping.bytes();
		}

		private ArenaSlice allocate(final long size, final long reservedSize, final long alignment) {
			long offset = align(this.cursor, alignment);
			if (offset > this.capacity || reservedSize > this.capacity - offset) return null;
			this.cursor = offset + reservedSize;
			GpuBufferSlice slice = this.buffer.slice(offset, size);
			ByteBuffer view = this.bytes.duplicate()
				.position(toInt(offset))
				.limit(toInt(offset + size))
				.slice()
				.order(ByteOrder.nativeOrder());
			return new ArenaSlice(slice, view);
		}

		private void reset() {
			this.cursor = 0L;
		}

		@Override
		public void close() {
			this.mapping.close();
			this.buffer.close();
		}
	}

	private record ArenaSlice(GpuBufferSlice slice, ByteBuffer bytes) {
	}

	private static long initialArenaSize(final long recommendedWorkingSet) {
		long automatic = Math.clamp(recommendedWorkingSet / 256L, MINIMUM_ARENA_SIZE, MAXIMUM_ARENA_SIZE);
		long configuredMiB = Long.getLong("metalcraft.transientArenaMiB", automatic / MEBIBYTE);
		return Math.multiplyExact(Math.clamp(configuredMiB, 1L, 1024L), MEBIBYTE);
	}

	private static long allocationSize(final long size, final long minimumAllocation, final long elementSize) {
		if (size <= 0L || minimumAllocation <= 0L || elementSize <= 0L) {
			throw new IllegalArgumentException("Transient allocation sizes must be positive");
		}
		long requested = Math.max(size, minimumAllocation);
		long remainder = requested % elementSize;
		return remainder == 0L ? requested : Math.addExact(requested, elementSize - remainder);
	}

	private static long totalSize(final List<ByteBuffer> data) {
		long size = 0L;
		for (ByteBuffer source : data) size = Math.addExact(size, source.remaining());
		if (size <= 0L) throw new IllegalArgumentException("Transient upload data cannot be empty");
		return size;
	}

	private static void copy(final List<ByteBuffer> sources, final ByteBuffer destination) {
		for (ByteBuffer source : sources) destination.put(source.duplicate());
	}

	private static long align(final long value, final long alignment) {
		long remainder = value % alignment;
		return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
	}

	private static void validateAlignment(final long alignment) {
		if (alignment <= 0L) throw new IllegalArgumentException("Transient allocation alignment must be positive");
	}

	private static long nextPowerOfTwo(final long value) {
		if (value <= 1L) return 1L;
		long highest = Long.highestOneBit(value - 1L);
		if (highest > (1L << 61)) throw new IllegalArgumentException("Transient allocation is too large");
		return highest << 1;
	}

	private static int toInt(final long size) {
		if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("A Java transient view cannot exceed 2 GiB");
		return (int)size;
	}
}
