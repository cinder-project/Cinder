package dev.cinder.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CinderChunkTest {

    // ── Factory ───────────────────────────────────────────────────────────

    @Test
    void generate_returnsChunkAtCorrectPosition() {
        ChunkPosition pos = ChunkPosition.of(3, -7);
        CinderChunk chunk = CinderChunk.generate(pos);
        assertEquals(pos, chunk.position);
    }

    @Test
    void generate_allBlocksAreAir() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertTrue(chunk.isEmpty());
    }

    @Test
    void generate_noSectionsAllocated() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertEquals(0, chunk.allocatedSectionCount());
    }

    @Test
    void generate_heapFootprintZeroWhenEmpty() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertEquals(0, chunk.heapFootprintBytes());
    }

    // ── Block read / write ────────────────────────────────────────────────

    @Test
    void setBlock_getBlock_roundtrips() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 1);
        assertEquals(1, chunk.getBlock(0, 64, 0));
    }

    @Test
    void setBlock_allocatesSection() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 0, 0, (short) 5);
        assertEquals(1, chunk.allocatedSectionCount());
    }

    @Test
    void setBlock_air_doesNotAllocateSection() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 0);
        assertEquals(0, chunk.allocatedSectionCount());
    }

    @Test
    void getBlock_unsetPosition_returnsAir() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertEquals(0, chunk.getBlock(5, 100, 5));
    }

    @Test
    void getBlock_outOfBoundsY_returnsAir() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertEquals(0, chunk.getBlock(0, 500, 0));
        assertEquals(0, chunk.getBlock(0, -200, 0));
    }

    @Test
    void isAir_unsetBlock() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertTrue(chunk.isAir(0, 64, 0));
    }

    @Test
    void isAir_setBlock() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 1);
        assertFalse(chunk.isAir(0, 64, 0));
    }

    @Test
    void setBlock_overwriteExisting() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 10);
        chunk.setBlock(0, 64, 0, (short) 20);
        assertEquals(20, chunk.getBlock(0, 64, 0));
    }

    @Test
    void setBlock_multiplePositionsInSameSection() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 1);
        chunk.setBlock(5, 64, 5, (short) 2);
        chunk.setBlock(15, 70, 15, (short) 3);
        assertEquals(1, chunk.allocatedSectionCount());
        assertEquals(1, chunk.getBlock(0, 64, 0));
        assertEquals(2, chunk.getBlock(5, 64, 5));
        assertEquals(3, chunk.getBlock(15, 70, 15));
    }

    @Test
    void setBlock_acrossMultipleSections() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, -64, 0, (short) 1);
        chunk.setBlock(0,   0, 0, (short) 2);
        chunk.setBlock(0, 128, 0, (short) 3);
        chunk.setBlock(0, 319, 0, (short) 4);
        assertEquals(4, chunk.allocatedSectionCount());
    }

    @Test
    void setBlock_toAirOnExistingSection_doesNotDeallocate() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 1);
        chunk.setBlock(1, 64, 0, (short) 2);
        chunk.setBlock(0, 64, 0, (short) 0);
        assertEquals(1, chunk.allocatedSectionCount());
        assertEquals(0, chunk.getBlock(0, 64, 0));
        assertEquals(2, chunk.getBlock(1, 64, 0));
    }

    // ── World height bounds ───────────────────────────────────────────────

    @Test
    void worldBounds_minY() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, CinderChunk.WORLD_MIN_Y, 0, (short) 1);
        assertEquals(1, chunk.getBlock(0, CinderChunk.WORLD_MIN_Y, 0));
    }

    @Test
    void worldBounds_maxY() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, CinderChunk.WORLD_MAX_Y, 0, (short) 1);
        assertEquals(1, chunk.getBlock(0, CinderChunk.WORLD_MAX_Y, 0));
    }

    @Test
    void sectionCount_matches118WorldHeight() {
        assertEquals(24, CinderChunk.SECTION_COUNT);
    }

    // ── Snapshot serialisation ────────────────────────────────────────────

    @Test
    void snapshot_emptyChunk_roundtrips() {
        ChunkPosition pos = ChunkPosition.of(5, -3);
        CinderChunk original = CinderChunk.generate(pos);
        byte[] snapshot = original.takeSnapshot();
        CinderChunk restored = CinderChunk.fromSnapshot(snapshot);
        assertEquals(pos, restored.position);
        assertTrue(restored.isEmpty());
    }

    @Test
    void snapshot_withBlocks_roundtrips() {
        ChunkPosition pos = ChunkPosition.of(1, 2);
        CinderChunk original = CinderChunk.generate(pos);
        original.setBlock(0, 64, 0, (short) 1);
        original.setBlock(7, 64, 7, (short) 42);
        original.setBlock(15, 100, 15, (short) 255);

        byte[] snapshot = original.takeSnapshot();
        CinderChunk restored = CinderChunk.fromSnapshot(snapshot);

        assertEquals(1,   restored.getBlock(0, 64, 0));
        assertEquals(42,  restored.getBlock(7, 64, 7));
        assertEquals(255, restored.getBlock(15, 100, 15));
    }

    @Test
    void snapshot_onlyNonNullSectionsSerialised() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 64, 0, (short) 1);
        chunk.setBlock(0, 0, 0, (short) 2);

        byte[] snapshot = chunk.takeSnapshot();
        CinderChunk restored = CinderChunk.fromSnapshot(snapshot);

        assertEquals(2, restored.allocatedSectionCount());
    }

    @Test
    void snapshot_emptyChunk_isSmall() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        byte[] snapshot = chunk.takeSnapshot();
        assertTrue(snapshot.length < 30, "Empty chunk snapshot should be tiny, was " + snapshot.length);
    }

    @Test
    void snapshot_correctPosition_negativeCoords() {
        ChunkPosition pos = ChunkPosition.of(-100, -200);
        CinderChunk chunk = CinderChunk.generate(pos);
        CinderChunk restored = CinderChunk.fromSnapshot(chunk.takeSnapshot());
        assertEquals(-100, restored.position.x);
        assertEquals(-200, restored.position.z);
    }

    @Test
    void fromSnapshot_invalidMagic_throws() {
        byte[] bad = new byte[30];
        bad[0] = 0x00;
        assertThrows(IllegalArgumentException.class, () -> CinderChunk.fromSnapshot(bad));
    }

    @Test
    void fromSnapshot_tooShort_throws() {
        assertThrows(IllegalArgumentException.class, () -> CinderChunk.fromSnapshot(new byte[4]));
    }

    @Test
    void fromSnapshot_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> CinderChunk.fromSnapshot(null));
    }

    // ── Holder system ─────────────────────────────────────────────────────

    @Test
    void holders_initiallyZero() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertEquals(0, chunk.getHolderCount());
    }

    @Test
    void holders_incrementAndGet() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.incrementHolders();
        chunk.incrementHolders();
        assertEquals(2, chunk.getHolderCount());
    }

    @Test
    void holders_decrementAndGet() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.incrementHolders();
        chunk.incrementHolders();
        chunk.decrementHolders();
        assertEquals(1, chunk.getHolderCount());
    }

    @Test
    void holders_decrementBelowZero_clampsToZero() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.decrementHolders();
        assertEquals(0, chunk.getHolderCount());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Test
    void onLoad_setsLoadedTrue() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        assertFalse(chunk.isLoaded());
        chunk.onLoad();
        assertTrue(chunk.isLoaded());
    }

    @Test
    void onUnload_setsLoadedFalse() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.onLoad();
        chunk.onUnload();
        assertFalse(chunk.isLoaded());
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    @Test
    void heapFootprint_growsWithSections() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(0, 0));
        chunk.setBlock(0, 0,   0, (short) 1);
        chunk.setBlock(0, 64,  0, (short) 2);
        chunk.setBlock(0, 128, 0, (short) 3);
        int expected = 3 * CinderChunk.BLOCKS_PER_SECTION * 2;
        assertEquals(expected, chunk.heapFootprintBytes());
    }

    @Test
    void toString_containsPosition() {
        CinderChunk chunk = CinderChunk.generate(ChunkPosition.of(7, -3));
        String s = chunk.toString();
        assertTrue(s.contains("7") && s.contains("-3"));
    }
}
