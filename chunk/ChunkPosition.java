package dev.cinder.chunk;

import java.util.Objects;

public final class ChunkPosition {

    public final int x;
    public final int z;

    private final int hashCode;

    public ChunkPosition(int x, int z) {
        this.x        = x;
        this.z        = z;
        this.hashCode = computeHash(x, z);
    }

    public static ChunkPosition of(int x, int z) {
        return new ChunkPosition(x, z);
    }

    public static ChunkPosition fromBlockCoords(int blockX, int blockZ) {
        return new ChunkPosition(blockX >> 4, blockZ >> 4);
    }

    public static ChunkPosition fromBlockCoords(double blockX, double blockZ) {
        return fromBlockCoords((int) Math.floor(blockX), (int) Math.floor(blockZ));
    }

    public long toLong() {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static ChunkPosition fromLong(long packed) {
        return new ChunkPosition(
            (int) (packed & 0xFFFFFFFFL),
            (int) ((packed >>> 32) & 0xFFFFFFFFL)
        );
    }

    public ChunkPosition north()     { return new ChunkPosition(x,     z - 1); }
    public ChunkPosition south()     { return new ChunkPosition(x,     z + 1); }
    public ChunkPosition east()      { return new ChunkPosition(x + 1, z);     }
    public ChunkPosition west()      { return new ChunkPosition(x - 1, z);     }
    public ChunkPosition offset(int dx, int dz) { return new ChunkPosition(x + dx, z + dz); }

    public int blockMinX() { return x << 4; }
    public int blockMinZ() { return z << 4; }
    public int blockMaxX() { return (x << 4) + 15; }
    public int blockMaxZ() { return (z << 4) + 15; }

    public double distanceTo(ChunkPosition other) {
        int dx = this.x - other.x;
        int dz = this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public int chebyshevDistance(ChunkPosition other) {
        return Math.max(Math.abs(this.x - other.x), Math.abs(this.z - other.z));
    }

    public boolean isWithinRadius(ChunkPosition centre, int chunkRadius) {
        return Math.abs(this.x - centre.x) <= chunkRadius
            && Math.abs(this.z - centre.z) <= chunkRadius;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ChunkPosition other)) return false;
        return this.x == other.x && this.z == other.z;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "ChunkPos(" + x + ", " + z + ")";
    }

    private static int computeHash(int x, int z) {
        return Objects.hash(x, z);
    }
}
