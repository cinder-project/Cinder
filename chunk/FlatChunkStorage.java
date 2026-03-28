package dev.cinder.chunk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FlatChunkStorage — binary chunk persistence optimised for Pi 4 storage.
 *
 * Storage layout:
 *   Each chunk is stored as a single file under a two-level directory
 *   hierarchy derived from chunk coordinates:
 *
 *     <worldDir>/chunks/r.<regionX>.<regionZ>/<chunkX>.<chunkZ>.cnk
 *
 *   Where regionX = chunkX >> 5, regionZ = chunkZ >> 5 (32x32 chunk regions).
 *   This mirrors Anvil's region directory concept but uses a flat binary
 *   format instead of the Anvil region file container.
 *
 * File format:
 *   Each .cnk file is the raw output of CinderChunk.takeSnapshot():
 *     [4 bytes]  magic: 0x43494E44 ('CIND')
 *     [4 bytes]  format version: 1
 *     [4 bytes]  chunk X
 *     [4 bytes]  chunk Z
 *     [2 bytes]  non-null section count
 *     per section:
 *       [1 byte]   section Y index
 *       [8192 bytes] block data (4096 shorts, little-endian)
 *
 * Pi 4 / microSD rationale:
 *   One-file-per-chunk avoids the write amplification of Anvil's 4KB sector
 *   alignment within region files. On microSD, writing a 8 KB dirty chunk
 *   in Anvil format requires reading and rewriting a 4 MB region file sector.
 *   With flat files, we write only the bytes that changed.
 *
 *   The two-level directory hierarchy keeps directory entry count manageable.
 *   A 32x32 chunk region (1024 chunks) produces at most 1024 files per
 *   region directory — within ext4's efficient range for small directories.
 *
 * Thread safety:
 *   All methods may be called from the IO executor thread.
 *   load() and saveAsync() are called exclusively from IO threads.
 *   saveSync() is called from the tick thread during forced unload/shutdown.
 *   No shared mutable state — each call operates on its own file path.
 */
public final class FlatChunkStorage implements ChunkLifecycleManager.ChunkStorage {

    private static final Logger LOG = Logger.getLogger("cinder.chunk.storage");

    private static final String CHUNK_EXTENSION = ".cnk";

    private final Path worldDir;

    public FlatChunkStorage(Path worldDir) {
        this.worldDir = worldDir;
    }

    // ── ChunkStorage implementation ───────────────────────────────────────

    @Override
    public CinderChunk load(ChunkPosition pos) throws Exception {
        Path file = chunkPath(pos);

        if (!Files.exists(file)) {
            return null;
        }

        byte[] data;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size > 10 * 1024 * 1024) {
                throw new IOException("Chunk file suspiciously large (" + size + " bytes): " + file);
            }
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            while (buf.hasRemaining()) {
                int read = ch.read(buf);
                if (read == -1) break;
            }
            data = buf.array();
        }

        try {
            CinderChunk chunk = CinderChunk.fromSnapshot(data);
            LOG.fine("[FlatStorage] Loaded chunk " + pos + " (" + data.length + " bytes)");
            return chunk;
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "[FlatStorage] Corrupt chunk file " + file + ": " + e.getMessage());
            backupCorrupt(file);
            return null;
        }
    }

    @Override
    public void saveAsync(ChunkPosition pos, byte[] snapshot) throws Exception {
        writeChunkFile(pos, snapshot);
    }

    @Override
    public void saveSync(ChunkPosition pos, CinderChunk chunk) throws Exception {
        byte[] snapshot = chunk.takeSnapshot();
        writeChunkFile(pos, snapshot);
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void writeChunkFile(ChunkPosition pos, byte[] data) throws IOException {
        Path file = chunkPath(pos);
        Path parent = file.getParent();

        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }

        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                              java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        LOG.fine("[FlatStorage] Saved chunk " + pos + " (" + data.length + " bytes)");
    }

    /**
     * Moves a corrupt chunk file to a .corrupt backup so it doesn't block
     * future load attempts, and preserves the data for forensics.
     */
    private void backupCorrupt(Path file) {
        try {
            Path backup = file.resolveSibling(
                file.getFileName() + ".corrupt." + System.currentTimeMillis());
            Files.move(file, backup);
            LOG.warning("[FlatStorage] Corrupt chunk backed up to: " + backup);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[FlatStorage] Could not back up corrupt chunk " + file, e);
        }
    }

    // ── Path derivation ───────────────────────────────────────────────────

    /**
     * Derives the chunk file path from its position.
     *
     * Example: chunk (34, -17) →
     *   chunks/r.1.-1/34.-17.cnk
     *   (regionX = 34>>5 = 1, regionZ = -17>>5 = -1)
     */
    private Path chunkPath(ChunkPosition pos) {
        int regionX = pos.x >> 5;
        int regionZ = pos.z >> 5;
        String regionDir = "r." + regionX + "." + regionZ;
        String fileName  = pos.x + "." + pos.z + CHUNK_EXTENSION;
        return worldDir.resolve("chunks").resolve(regionDir).resolve(fileName);
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    /**
     * Returns the number of chunk files currently stored on disk.
     * Walks the chunk directory — intended for diagnostics, not the hot path.
     */
    public long countStoredChunks() {
        Path chunksDir = worldDir.resolve("chunks");
        if (!Files.exists(chunksDir)) return 0L;

        try (var stream = Files.walk(chunksDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(CHUNK_EXTENSION))
                .count();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[FlatStorage] Could not count chunk files", e);
            return -1L;
        }
    }

    /**
     * Returns the total disk usage of the chunk store in bytes.
     */
    public long totalDiskBytes() {
        Path chunksDir = worldDir.resolve("chunks");
        if (!Files.exists(chunksDir)) return 0L;

        try (var stream = Files.walk(chunksDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(CHUNK_EXTENSION))
                .mapToLong(p -> {
                    try { return Files.size(p); }
                    catch (IOException e) { return 0L; }
                })
                .sum();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[FlatStorage] Could not compute disk usage", e);
            return -1L;
        }
    }

    public Path getWorldDir() { return worldDir; }

    @Override
    public String toString() {
        return "FlatChunkStorage{worldDir=" + worldDir + "}";
    }
}
