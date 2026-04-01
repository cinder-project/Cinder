package dev.cinder.world;

import dev.cinder.chunk.ChunkPosition;
import dev.cinder.chunk.CinderChunk;

import java.util.Objects;

/**
 * Deterministic flat-world generator for Phase 3 bring-up.
 *
 * Terrain profile (absolute world Y):
 *   y=0   -> bedrock
 *   y=1-3 -> dirt
 *   y=4   -> grass block
 *   else  -> air
 */
public final class FlatWorldGenerator {

    // Vanilla block-state IDs (1.21.x) for default states.
    public static final short AIR_STATE       = 0;
    public static final short GRASS_STATE     = 9;
    public static final short DIRT_STATE      = 10;
    public static final short BEDROCK_STATE   = 79;

    public static final int BEDROCK_Y         = 0;
    public static final int DIRT_MIN_Y        = 1;
    public static final int DIRT_MAX_Y        = 3;
    public static final int SURFACE_Y         = 4;

    /**
     * Create and populate a new flat chunk at {@code position}.
     */
    public CinderChunk generate(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        CinderChunk chunk = CinderChunk.generate(position);
        populate(chunk);
        return chunk;
    }

    /**
     * Populate an existing chunk with the flat terrain profile.
     */
    public void populate(CinderChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        int worldMinX = chunk.position.blockMinX();
        int worldMinZ = chunk.position.blockMinZ();

        for (int localX = 0; localX < 16; localX++) {
            int worldX = worldMinX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = worldMinZ + localZ;

                chunk.setBlock(worldX, BEDROCK_Y, worldZ, BEDROCK_STATE);
                for (int y = DIRT_MIN_Y; y <= DIRT_MAX_Y; y++) {
                    chunk.setBlock(worldX, y, worldZ, DIRT_STATE);
                }
                chunk.setBlock(worldX, SURFACE_Y, worldZ, GRASS_STATE);
            }
        }
    }
}