package dev.cinder.world;

import dev.cinder.chunk.ChunkPosition;
import dev.cinder.chunk.CinderChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlatWorldGeneratorTest {

    @Test
    void generate_populatesExpectedFlatLayers() {
        FlatWorldGenerator generator = new FlatWorldGenerator();
        CinderChunk chunk = generator.generate(ChunkPosition.of(0, 0));

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(FlatWorldGenerator.BEDROCK_STATE, chunk.getBlock(x, 0, z));
                assertEquals(FlatWorldGenerator.DIRT_STATE, chunk.getBlock(x, 1, z));
                assertEquals(FlatWorldGenerator.DIRT_STATE, chunk.getBlock(x, 2, z));
                assertEquals(FlatWorldGenerator.DIRT_STATE, chunk.getBlock(x, 3, z));
                assertEquals(FlatWorldGenerator.GRASS_STATE, chunk.getBlock(x, 4, z));
                assertEquals(FlatWorldGenerator.AIR_STATE, chunk.getBlock(x, 5, z));
            }
        }
    }

    @Test
    void generate_handlesNegativeChunkCoordinates() {
        FlatWorldGenerator generator = new FlatWorldGenerator();
        CinderChunk chunk = generator.generate(ChunkPosition.of(-2, -3));

        int minX = chunk.position.blockMinX();
        int minZ = chunk.position.blockMinZ();

        assertEquals(FlatWorldGenerator.BEDROCK_STATE, chunk.getBlock(minX, 0, minZ));
        assertEquals(FlatWorldGenerator.DIRT_STATE, chunk.getBlock(minX + 15, 2, minZ + 15));
        assertEquals(FlatWorldGenerator.GRASS_STATE, chunk.getBlock(minX + 7, 4, minZ + 8));
        assertEquals(FlatWorldGenerator.AIR_STATE, chunk.getBlock(minX + 7, 20, minZ + 8));
    }

    @Test
    void generate_allocatesSingleSectionForFlatLayerStack() {
        FlatWorldGenerator generator = new FlatWorldGenerator();
        CinderChunk chunk = generator.generate(ChunkPosition.of(0, 0));

        assertEquals(1, chunk.allocatedSectionCount());
    }
}