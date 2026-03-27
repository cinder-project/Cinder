package dev.cinder.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * CinderChunk — chunk data model for Cinder Core.
 *
 * Storage layout:
 *   A chunk is 16×16 blocks across and 384 sections tall (Y: -64 to 319),
 *   matching the Minecraft 1.18+ world height. Each section is 16×16×16
 *   blocks = 4096 block entries.
 *
 *   Block IDs are stored as shorts (2 bytes), giving a palette range of
 *   0–65535. This is sufficient for vanilla block state IDs and leaves
 *   headroom for Cinder's internal block types.
 *
 *   Sections are lazily allocated: a null section slot means all blocks
 *   in that section are air (ID 0). This avoids allocating 24 MB per chunk
 *   for worlds with large air columns.
 *
 * Snapshot format:
 *   takeSnapshot() produces a compact byte[] for async serialisation by
 *   ChunkLifecycleManager. The format is:
 *
 *     [4 bytes]  magic: 0x43494E44 ('CIND')
 *     [4 bytes]  format version: 1
 *     [4 bytes]  chunk X
 *     [4 bytes]  chunk Z
 *     [2 bytes]  section count (non-null sections only)
 *     per section:
 *       [1 byte]   section Y index (0–23)
 *       [8192 bytes] block data (4096 shorts, little-endian)
 *
 *   Empty chunks (all air) serialise to a 22-byte header with section
 *   count = 0. This is the common case for newly generated flat terrain.
 *
 * Holder system:
 *   Any external system that requires this chunk to remain loaded
 *   (a player's view distance, an entity in this chunk, a pending
 *   block operation) increments the holder count via incrementHolders().
 *   ChunkLifecycleManager will not evict a chunk with holders > 0.
 *   The holder count is an AtomicInteger — safe to read from any thread,
 *   though increment/decrement should only happen on the tick thread.
 *
 * Thread safety:
 *   Block read/write methods are tick-thread-only. The holder count is
 *   atomic for safe cross-thread reads (e.g., from the LRU eviction check).
 *   takeSnapshot() copies block data under no lock — it must only be called
 *   on the tick thread to ensure a consistent view.
 *
 * ARM64 / Pi 4 notes:
 *   Section arrays are allocated as short[] to keep block data compact.
 *   At 4096 shorts per section and 24 sections max, a fully populated chunk
 *   uses 196 KB of heap. With lazy section allocation, sparse chunks (caves,
 *   sky) use a fraction of that.
 */
public final class CinderChunk {

    private static final Logger LOG = Logger.getLogger("cinder.chunk");

    public static final int SECTION_WIDTH  = 16;
    public static final int SECTION_HEIGHT = 16;
    public static final int SECTION_DEPTH  = 16;
    public static final int BLOCKS_PER_SECTION = SECTION_WIDTH * SECTION_HEIGHT * SECTION_DEPTH;

    public static final int WORLD_MIN_Y    = -64;
    public static final int WORLD_MAX_Y    = 319;
    public static final int SECTION_COUNT  = (WORLD_MAX_Y - WORLD_MIN_Y + 1) / SECTION_HEIGHT;  // 24

    private static final int SNAPSHOT_MAGIC   = 0x43494E44;
    private static final int SNAPSHOT_VERSION = 1;

    private static final short AIR = 0;

    private static final int HEADER_BYTES   = 4 + 4 + 4 + 4 + 2;  // magic + version + x + z + sectionCount
    private static final int SECTION_BYTES  = 1 + BLOCKS_PER_SECTION * 2;

    // ── Identity ──────────────────────────────────────────────────────────

    public final ChunkPosition position;

    // ── Block storage (tick thread only) ─────────────────────────────────

    /**
     * Sparse section array. Each slot corresponds to a 16-block-tall section.
     * Index 0 = Y -64 to -49, index 23 = Y 304 to 319.
     * Null slots are implicitly all-air.
     */
    private final short[][] sections = new short[SECTION_COUNT][];

    // ── State ─────────────────────────────────────────────────────────────

    private final AtomicInteger holderCount = new AtomicInteger(0);
    private volatile boolean loaded = false;

    // ── Constructor (use factory methods) ────────────────────────────────

    private CinderChunk(ChunkPosition position) {
        this.position = position;
    }

    // ── Factory methods ───────────────────────────────────────────────────

    /**
     * Generates a new empty chunk at the given position.
     * All blocks default to air. Called by ChunkLifecycleManager when a
     * requested chunk does not exist on disk.
     */
    public static CinderChunk generate(ChunkPosition position) {
        CinderChunk chunk = new CinderChunk(position);
        LOG.fine("[CinderChunk] Generated empty chunk at " + position);
        return chunk;
    }

    /**
     * Deserialises a CinderChunk from a snapshot byte array produced by
     * takeSnapshot(). Used by ChunkStorage implementations on the IO thread.
     *
     * @throws IllegalArgumentException if the snapshot is malformed or version-mismatched
     */
    public static CinderChunk fromSnapshot(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Snapshot too short: " + (data == null ? 0 : data.length));
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt();
        if (magic != SNAPSHOT_MAGIC) {
            throw new IllegalArgumentException(
                "Invalid snapshot magic: 0x" + Integer.toHexString(magic));
        }

        int version = buf.getInt();
        if (version != SNAPSHOT_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported snapshot version: " + version);
        }

        int chunkX = buf.getInt();
        int chunkZ = buf.getInt();
        int sectionCount = buf.getShort() & 0xFFFF;

        CinderChunk chunk = new CinderChunk(ChunkPosition.of(chunkX, chunkZ));

        for (int i = 0; i < sectionCount; i++) {
            int sectionIndex = buf.get() & 0xFF;
            if (sectionIndex >= SECTION_COUNT) {
                throw new IllegalArgumentException("Invalid section index: " + sectionIndex);
            }
            short[] blocks = new short[BLOCKS_PER_SECTION];
            for (int b = 0; b < BLOCKS_PER_SECTION; b++) {
                blocks[b] = buf.getShort();
            }
            chunk.sections[sectionIndex] = blocks;
        }

        return chunk;
    }

    // ── Block access (tick thread only) ───────────────────────────────────

    /**
     * Returns the block ID at the given world coordinates.
     * Returns 0 (air) for unallocated sections.
     *
     * @param x  World X (any value; chunk boundary is not enforced for flexibility)
     * @param y  World Y (-64 to 319)
     * @param z  World Z
     */
    public short getBlock(int x, int y, int z) {
        int sectionIndex = sectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= SECTION_COUNT) return AIR;

        short[] section = sections[sectionIndex];
        if (section == null) return AIR;

        return section[blockIndex(x, y, z)];
    }

    /**
     * Sets the block ID at the given world coordinates.
     * Allocates a section on first write if not already present.
     *
     * @param x       World X
     * @param y       World Y (-64 to 319)
     * @param z       World Z
     * @param blockId Block state ID (0 = air)
     */
    public void setBlock(int x, int y, int z, short blockId) {
        int sectionIndex = sectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= SECTION_COUNT) return;

        if (blockId == AIR) {
            short[] section = sections[sectionIndex];
            if (section != null) {
                section[blockIndex(x, y, z)] = AIR;
            }
            return;
        }

        if (sections[sectionIndex] == null) {
            sections[sectionIndex] = new short[BLOCKS_PER_SECTION];
        }

        sections[sectionIndex][blockIndex(x, y, z)] = blockId;
    }

    /**
     * Returns true if the block at the given position is air.
     */
    public boolean isAir(int x, int y, int z) {
        return getBlock(x, y, z) == AIR;
    }

    /**
     * Returns true if this chunk has no non-air blocks (all sections are null or empty).
     */
    public boolean isEmpty() {
        for (short[] section : sections) {
            if (section != null) {
                for (short block : section) {
                    if (block != AIR) return false;
                }
            }
        }
        return true;
    }

    // ── Snapshot serialisation (tick thread only) ─────────────────────────

    /**
     * Serialises this chunk into a compact byte array for async disk writes.
     * Only non-null (non-air) sections are included.
     *
     * The returned array is a fresh allocation — the caller owns it and may
     * pass it to an IO thread without synchronisation.
     */
    public byte[] takeSnapshot() {
        int nonNullSections = 0;
        for (short[] section : sections) {
            if (section != null) nonNullSections++;
        }

        int totalBytes = HEADER_BYTES + nonNullSections * SECTION_BYTES;
        ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(SNAPSHOT_MAGIC);
        buf.putInt(SNAPSHOT_VERSION);
        buf.putInt(position.x);
        buf.putInt(position.z);
        buf.putShort((short) nonNullSections);

        for (int i = 0; i < SECTION_COUNT; i++) {
            short[] section = sections[i];
            if (section == null) continue;
            buf.put((byte) i);
            for (short block : section) {
                buf.putShort(block);
            }
        }

        return buf.array();
    }

    // ── Holder system ─────────────────────────────────────────────────────

    /**
     * Returns the current holder count. Safe to call from any thread.
     * A non-zero count prevents LRU eviction in ChunkLifecycleManager.
     */
    public int getHolderCount() {
        return holderCount.get();
    }

    /**
     * Increments the holder count. Should only be called on the tick thread.
     */
    public void incrementHolders() {
        holderCount.incrementAndGet();
    }

    /**
     * Decrements the holder count. Should only be called on the tick thread.
     * Logs a warning if the count would go negative (indicates a release/acquire mismatch).
     */
    public void decrementHolders() {
        int result = holderCount.decrementAndGet();
        if (result < 0) {
            LOG.warning("[CinderChunk] Holder count went negative for " + position
                + " — mismatched addHolder/removeHolder calls.");
            holderCount.set(0);
        }
    }

    // ── Lifecycle hooks ───────────────────────────────────────────────────

    /**
     * Called by ChunkLifecycleManager on the tick thread immediately after
     * the chunk is promoted into the cache.
     */
    public void onLoad() {
        this.loaded = true;
        LOG.fine("[CinderChunk] Loaded: " + position);
    }

    /**
     * Called by ChunkLifecycleManager on the tick thread immediately before
     * the chunk is removed from the cache (eviction or forced unload).
     */
    public void onUnload() {
        this.loaded = false;
        LOG.fine("[CinderChunk] Unloaded: " + position);
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ── Coordinate helpers ────────────────────────────────────────────────

    private static int sectionIndex(int worldY) {
        return (worldY - WORLD_MIN_Y) >> 4;
    }

    private static int blockIndex(int x, int y, int z) {
        int lx = x & 0xF;
        int ly = y & 0xF;
        int lz = z & 0xF;
        return (ly << 8) | (lz << 4) | lx;
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    /**
     * Returns the number of allocated (non-null) sections in this chunk.
     * Each allocated section contributes 8 KB of heap.
     */
    public int allocatedSectionCount() {
        int count = 0;
        for (short[] section : sections) {
            if (section != null) count++;
        }
        return count;
    }

    /**
     * Returns the approximate heap footprint of this chunk's block storage in bytes.
     */
    public int heapFootprintBytes() {
        return allocatedSectionCount() * BLOCKS_PER_SECTION * 2;
    }

    @Override
    public String toString() {
        return "CinderChunk{pos=" + position
            + ", sections=" + allocatedSectionCount() + "/" + SECTION_COUNT
            + ", holders=" + holderCount.get()
            + ", loaded=" + loaded + "}";
    }
}
