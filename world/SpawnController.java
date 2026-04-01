package dev.cinder.world;

import dev.cinder.chunk.ChunkPosition;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunk-region mob cap guard used by world spawn systems.
 */
public final class SpawnController {

    private final Map<Long, Integer> regionCounts = new HashMap<>();
    private int maxPerRegion = 40;
    private int regionSizeInChunks = 4;

    public void setMaxPerRegion(int maxPerRegion) {
        this.maxPerRegion = Math.max(1, maxPerRegion);
    }

    public void setRegionSizeInChunks(int regionSizeInChunks) {
        this.regionSizeInChunks = Math.max(1, regionSizeInChunks);
    }

    public boolean canSpawn(ChunkPosition chunkPosition) {
        long key = regionKey(chunkPosition);
        int current = regionCounts.getOrDefault(key, 0);
        return current < maxPerRegion;
    }

    public void onSpawn(ChunkPosition chunkPosition) {
        long key = regionKey(chunkPosition);
        regionCounts.put(key, regionCounts.getOrDefault(key, 0) + 1);
    }

    public void onDespawn(ChunkPosition chunkPosition) {
        long key = regionKey(chunkPosition);
        int current = regionCounts.getOrDefault(key, 0);
        if (current <= 1) {
            regionCounts.remove(key);
        } else {
            regionCounts.put(key, current - 1);
        }
    }

    private long regionKey(ChunkPosition chunkPosition) {
        int rx = Math.floorDiv(chunkPosition.x, regionSizeInChunks);
        int rz = Math.floorDiv(chunkPosition.z, regionSizeInChunks);
        return (((long) rx) << 32) ^ (rz & 0xffffffffL);
    }
}
