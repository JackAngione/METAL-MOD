package dev.metalcraft.client.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A run of render-pass binds and draws, recorded into one direct buffer and replayed by a single
 * JNI call.
 *
 * <p>Every bind and every draw used to be a synchronized Java method wrapping its own JNI call,
 * which then took the native registry's global lock, resolved its handles, and pinned its resources
 * before issuing the one Metal call it existed for. A chunk-section pass makes three of those per
 * section - a vertex buffer, a dynamic uniform, and the draw - so the fixed cost was paid thousands
 * of times per frame to issue thousands of encoder calls. This records the same commands into a
 * flat array of fixed-size records and hands the whole array over at once, so the crossing, the
 * lock, and the Java monitor are paid per batch rather than per command. The Metal encoder calls
 * themselves are unchanged: the batch is replayed in recording order, so what the GPU sees is what
 * the immediate path would have produced.
 *
 * <p><b>Validation lives here.</b> The coarse native path re-checks only what it can answer from a
 * record's own fields - opcodes, slot indices, stage masks, counts - because those are integer
 * compares. It does not re-derive buffer lengths from the Metal objects, which would cost a message
 * send per command. So the range checks below are the ones that keep a bad offset away from Metal,
 * and they are the same checks {@link MetalRenderPass}'s immediate setters make. The checked ABI,
 * enabled by {@code -Dmetalcraft.checkedCommands=true}, turns the native range checks back on and
 * names the offending command by index; it is for proving the two paths agree, not for shipping.
 *
 * <p>Records are fixed width rather than packed per opcode so that a decoder is an array index and
 * an ABI mismatch is a single comparison against the record size in the header. A record is 48
 * bytes and a frame's worth of chunk sections is a few hundred kilobytes, written sequentially into
 * a buffer that is allocated once per encoder and reused for the process's lifetime.
 */
public final class MetalCommandStream {
	/** {@code 'MCMD'}, so an uninitialised or wrongly aimed buffer is rejected rather than decoded. */
	static final int MAGIC = 0x4D434D44;
	/** {@code magic}, {@code commandSize}, {@code commandCount}, {@code flags}. */
	static final int HEADER_BYTES = 16;
	/** Must equal {@code sizeof(MCCommand)} in {@code metalcraft.m}, which static-asserts its size. */
	static final int COMMAND_BYTES = 48;

	static final int OP_SET_VERTEX_BUFFER = 1;
	static final int OP_SET_UNIFORM_BUFFER = 2;
	static final int OP_SET_TEXTURE = 3;
	static final int OP_SET_SAMPLER = 4;
	static final int OP_DRAW_INDEXED = 5;
	static final int OP_SET_PIPELINE = 6;

	/** Asks the decoder to re-validate every command against the Metal objects it names. */
	static final int FLAG_CHECKED = 1;

	private static final int FIELD_OPCODE = 0;
	/** The binding index, or a {@link MetalRenderPass.Primitive} ordinal for a draw. */
	private static final int FIELD_SLOT = 4;
	/** A {@code MetalRenderPass.STAGE_*} mask, or a {@link MetalRenderPass.IndexType} ordinal for a draw. */
	private static final int FIELD_STAGES = 8;
	private static final int FIELD_COUNT = 12;
	private static final int FIELD_INSTANCE_COUNT = 16;
	private static final int FIELD_BASE_VERTEX = 20;
	private static final int FIELD_BASE_INSTANCE = 24;
	/** Zero on every record; the decoder rejects anything else, which catches ABI drift early. */
	private static final int FIELD_RESERVED = 28;
	private static final int FIELD_HANDLE = 32;
	private static final int FIELD_OFFSET = 40;

	static {
		// The reserved field is what the decoder reads to catch a record layout that has drifted, so
		// it has to sit inside the record rather than past its end.
		if (FIELD_OFFSET + Long.BYTES != COMMAND_BYTES || FIELD_RESERVED + Integer.BYTES != FIELD_HANDLE) {
			throw new AssertionError("Metal command record fields do not fill " + COMMAND_BYTES + " bytes");
		}
	}

	/** Enough for a chunk-section pass without growing; 48 KiB, allocated once and reused. */
	private static final int INITIAL_COMMANDS = 1024;

	/**
	 * Whether the native decoder re-validates each command against the objects it names.
	 *
	 * <p>Not final because the shader smoke test runs the same draws through both ABIs and compares
	 * the pixels, which is the only thing that can show the coarse path agreeing with the checked
	 * one.
	 */
	private static boolean checked = Boolean.getBoolean("metalcraft.checkedCommands");

	private ByteBuffer bytes = allocate(INITIAL_COMMANDS);
	private int capacity = INITIAL_COMMANDS;
	private int count;

	/** @return whether the native decoder is re-validating each command */
	public static boolean isChecked() {
		return checked;
	}

	/** @see #checked */
	static void setChecked(final boolean value) {
		checked = value;
	}

	/** Discards whatever was recorded, keeping the buffer. */
	public void reset() {
		this.count = 0;
	}

	public int commandCount() {
		return this.count;
	}

	int byteCount() {
		return HEADER_BYTES + this.count * COMMAND_BYTES;
	}

	/** Stamps the header over the recorded commands and returns the buffer to hand to native code. */
	ByteBuffer prepared() {
		this.bytes.putInt(0, MAGIC);
		this.bytes.putInt(4, COMMAND_BYTES);
		this.bytes.putInt(8, this.count);
		this.bytes.putInt(12, checked ? FLAG_CHECKED : 0);
		return this.bytes;
	}

	/** Only the owning pass records this, after checking its attachment formats. */
	void setPipeline(final MetalRenderPipeline pipeline) {
		this.claim(OP_SET_PIPELINE, pipeline.requireOpenHandle());
	}

	/** @see MetalRenderPass#setVertexBuffer(int, MetalBuffer, long) */
	public void setVertexBuffer(final int index, final MetalBuffer buffer, final long offset) {
		if (index < 0 || index >= MetalRenderPass.VERTEX_BUFFER_SLOTS) {
			throw new IllegalArgumentException("Metal vertex buffer index must be between 0 and " + (MetalRenderPass.VERTEX_BUFFER_SLOTS - 1));
		}
		MetalBuffer.checkRange(buffer.size(), offset, 1L, "vertex binding");
		int record = this.claim(OP_SET_VERTEX_BUFFER, buffer.requireOpenHandle());
		this.bytes.putInt(record + FIELD_SLOT, index);
		this.bytes.putLong(record + FIELD_OFFSET, offset);
	}

	/** @see MetalRenderPass#setUniformBuffer(int, MetalBuffer, long, int) */
	public void setUniformBuffer(final int index, final MetalBuffer buffer, final long offset, final int stages) {
		if (index < 0 || index >= MetalRenderPass.RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal uniform buffer index must be between 0 and 15");
		}
		if (MetalRenderPass.checkedStages(stages) == 0) {
			return;
		}
		MetalBuffer.checkRange(buffer.size(), offset, 1L, "uniform binding");
		int record = this.claim(OP_SET_UNIFORM_BUFFER, buffer.requireOpenHandle());
		this.bytes.putInt(record + FIELD_SLOT, index);
		this.bytes.putInt(record + FIELD_STAGES, stages);
		this.bytes.putLong(record + FIELD_OFFSET, offset);
	}

	/** @see MetalRenderPass#setTexture(int, MetalTextureView, int) */
	public void setTexture(final int index, final MetalTextureView textureView, final int stages) {
		if (index < 0 || index >= MetalRenderPass.RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal texture index must be between 0 and 15");
		}
		if (MetalRenderPass.checkedStages(stages) == 0) {
			return;
		}
		int record = this.claim(OP_SET_TEXTURE, textureView.requireOpenHandle());
		this.bytes.putInt(record + FIELD_SLOT, index);
		this.bytes.putInt(record + FIELD_STAGES, stages);
	}

	/** @see MetalRenderPass#setSampler(int, MetalSampler, int) */
	public void setSampler(final int index, final MetalSampler sampler, final int stages) {
		if (index < 0 || index >= MetalRenderPass.RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal sampler index must be between 0 and 15");
		}
		if (MetalRenderPass.checkedStages(stages) == 0) {
			return;
		}
		int record = this.claim(OP_SET_SAMPLER, sampler.requireOpenHandle());
		this.bytes.putInt(record + FIELD_SLOT, index);
		this.bytes.putInt(record + FIELD_STAGES, stages);
	}

	/** @see MetalRenderPass#drawIndexed */
	public void drawIndexed(
		final MetalRenderPass.Primitive primitive,
		final MetalBuffer indexBuffer,
		final long indexBufferOffset,
		final MetalRenderPass.IndexType indexType,
		final int indexCount,
		final int instanceCount,
		final int baseVertex,
		final int baseInstance
	) {
		if (primitive == null || indexType == null) {
			throw new NullPointerException("Metal draw primitive and index type cannot be null");
		}
		if (indexCount < 0 || instanceCount < 0) {
			throw new IllegalArgumentException("Metal draw counts cannot be negative");
		}
		if (baseInstance < 0 || indexBufferOffset % indexType.bytes != 0L) {
			throw new IllegalArgumentException("Metal index-buffer offsets must be aligned and base instance cannot be negative");
		}
		if (indexCount == 0 || instanceCount == 0) {
			return;
		}
		long indexBytes = Math.multiplyExact((long)indexCount, indexType.bytes);
		MetalBuffer.checkRange(indexBuffer.size(), indexBufferOffset, indexBytes, "index binding");
		int record = this.claim(OP_DRAW_INDEXED, indexBuffer.requireOpenHandle());
		this.bytes.putInt(record + FIELD_SLOT, primitive.ordinal());
		this.bytes.putInt(record + FIELD_STAGES, indexType.ordinal());
		this.bytes.putInt(record + FIELD_COUNT, indexCount);
		this.bytes.putInt(record + FIELD_INSTANCE_COUNT, instanceCount);
		this.bytes.putInt(record + FIELD_BASE_VERTEX, baseVertex);
		this.bytes.putInt(record + FIELD_BASE_INSTANCE, baseInstance);
		this.bytes.putLong(record + FIELD_OFFSET, indexBufferOffset);
	}

	/**
	 * Appends an empty record and returns its offset.
	 *
	 * <p>Every field the opcode does not use is zeroed here rather than left as whatever the
	 * previous batch wrote, so a record means the same thing however the buffer has been reused.
	 */
	private int claim(final int opcode, final long handle) {
		if (this.count == this.capacity) {
			this.grow();
		}
		int record = HEADER_BYTES + this.count * COMMAND_BYTES;
		this.count++;
		this.bytes.putInt(record + FIELD_SLOT, 0);
		this.bytes.putLong(record + FIELD_STAGES, 0L);
		this.bytes.putLong(record + FIELD_INSTANCE_COUNT, 0L);
		this.bytes.putLong(record + FIELD_BASE_INSTANCE, 0L);
		this.bytes.putLong(record + FIELD_OFFSET, 0L);
		this.bytes.putInt(record + FIELD_OPCODE, opcode);
		this.bytes.putLong(record + FIELD_HANDLE, handle);
		return record;
	}

	private void grow() {
		int grown = Math.multiplyExact(this.capacity, 2);
		ByteBuffer replacement = allocate(grown);
		replacement.put(0, this.bytes, 0, this.byteCount());
		this.bytes = replacement;
		this.capacity = grown;
	}

	private static ByteBuffer allocate(final int commands) {
		return ByteBuffer
			.allocateDirect(HEADER_BYTES + Math.multiplyExact(commands, COMMAND_BYTES))
			.order(ByteOrder.nativeOrder());
	}
}
