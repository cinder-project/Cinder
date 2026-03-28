package dev.cinder.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkPositionTest {

    // ── Construction ──────────────────────────────────────────────────────

    @Test
    void of_storesCoordinates() {
        ChunkPosition pos = ChunkPosition.of(3, -7);
        assertEquals(3,  pos.x);
        assertEquals(-7, pos.z);
    }

    @Test
    void fromBlockCoords_positive() {
        ChunkPosition pos = ChunkPosition.fromBlockCoords(32, 48);
        assertEquals(2, pos.x);
        assertEquals(3, pos.z);
    }

    @Test
    void fromBlockCoords_negative() {
        ChunkPosition pos = ChunkPosition.fromBlockCoords(-1, -17);
        assertEquals(-1, pos.x);
        assertEquals(-2, pos.z);
    }

    @Test
    void fromBlockCoords_chunkBoundary() {
        ChunkPosition pos = ChunkPosition.fromBlockCoords(16, -16);
        assertEquals(1,  pos.x);
        assertEquals(-1, pos.z);
    }

    @Test
    void fromBlockCoords_double_floors() {
        ChunkPosition pos = ChunkPosition.fromBlockCoords(15.9, -0.1);
        assertEquals(0,  pos.x);
        assertEquals(-1, pos.z);
    }

    // ── Long packing ──────────────────────────────────────────────────────

    @Test
    void toLong_roundtrips_positive() {
        ChunkPosition original = ChunkPosition.of(12, 34);
        assertEquals(original, ChunkPosition.fromLong(original.toLong()));
    }

    @Test
    void toLong_roundtrips_negative() {
        ChunkPosition original = ChunkPosition.of(-1, -1);
        assertEquals(original, ChunkPosition.fromLong(original.toLong()));
    }

    @Test
    void toLong_roundtrips_extremes() {
        ChunkPosition original = ChunkPosition.of(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(original, ChunkPosition.fromLong(original.toLong()));
    }

    @Test
    void toLong_distinctForTransposedCoords() {
        assertNotEquals(
            ChunkPosition.of(1, 0).toLong(),
            ChunkPosition.of(0, 1).toLong()
        );
    }

    // ── Equality and hashing ──────────────────────────────────────────────

    @Test
    void equals_sameCoords() {
        assertEquals(ChunkPosition.of(5, 5), ChunkPosition.of(5, 5));
    }

    @Test
    void equals_differentX() {
        assertNotEquals(ChunkPosition.of(1, 5), ChunkPosition.of(2, 5));
    }

    @Test
    void equals_differentZ() {
        assertNotEquals(ChunkPosition.of(5, 1), ChunkPosition.of(5, 2));
    }

    @Test
    void hashCode_equalObjects() {
        assertEquals(
            ChunkPosition.of(7, -3).hashCode(),
            ChunkPosition.of(7, -3).hashCode()
        );
    }

    @Test
    void usableAsHashMapKey() {
        Map<ChunkPosition, String> map = new HashMap<>();
        map.put(ChunkPosition.of(10, 20), "value");
        assertEquals("value", map.get(ChunkPosition.of(10, 20)));
    }

    @Test
    void hashMapKey_negativeCoords() {
        Map<ChunkPosition, Integer> map = new HashMap<>();
        map.put(ChunkPosition.of(-5, -10), 42);
        assertEquals(42, map.get(ChunkPosition.of(-5, -10)));
    }

    // ── Neighbours ────────────────────────────────────────────────────────

    @Test
    void north_decrementsZ() {
        assertEquals(ChunkPosition.of(0, -1), ChunkPosition.of(0, 0).north());
    }

    @Test
    void south_incrementsZ() {
        assertEquals(ChunkPosition.of(0, 1), ChunkPosition.of(0, 0).south());
    }

    @Test
    void east_incrementsX() {
        assertEquals(ChunkPosition.of(1, 0), ChunkPosition.of(0, 0).east());
    }

    @Test
    void west_decrementsX() {
        assertEquals(ChunkPosition.of(-1, 0), ChunkPosition.of(0, 0).west());
    }

    @Test
    void offset_appliesDelta() {
        assertEquals(ChunkPosition.of(3, -2), ChunkPosition.of(1, 0).offset(2, -2));
    }

    @Test
    void neighbours_doNotMutateOriginal() {
        ChunkPosition origin = ChunkPosition.of(5, 5);
        origin.north();
        origin.south();
        origin.east();
        origin.west();
        assertEquals(ChunkPosition.of(5, 5), origin);
    }

    // ── Block coordinate ranges ───────────────────────────────────────────

    @Test
    void blockMin_positiveChunk() {
        ChunkPosition pos = ChunkPosition.of(2, 3);
        assertEquals(32, pos.blockMinX());
        assertEquals(48, pos.blockMinZ());
    }

    @Test
    void blockMax_positiveChunk() {
        ChunkPosition pos = ChunkPosition.of(2, 3);
        assertEquals(47, pos.blockMaxX());
        assertEquals(63, pos.blockMaxZ());
    }

    @Test
    void blockMin_negativeChunk() {
        ChunkPosition pos = ChunkPosition.of(-1, -1);
        assertEquals(-16, pos.blockMinX());
        assertEquals(-16, pos.blockMinZ());
    }

    @Test
    void blockMax_negativeChunk() {
        ChunkPosition pos = ChunkPosition.of(-1, -1);
        assertEquals(-1, pos.blockMaxX());
        assertEquals(-1, pos.blockMaxZ());
    }

    @Test
    void blockRange_spansSixteenBlocks() {
        ChunkPosition pos = ChunkPosition.of(0, 0);
        assertEquals(15, pos.blockMaxX() - pos.blockMinX());
        assertEquals(15, pos.blockMaxZ() - pos.blockMinZ());
    }

    // ── Distance ─────────────────────────────────────────────────────────

    @Test
    void chebyshevDistance_axisAligned() {
        ChunkPosition origin = ChunkPosition.of(0, 0);
        assertEquals(5, origin.chebyshevDistance(ChunkPosition.of(5, 0)));
        assertEquals(5, origin.chebyshevDistance(ChunkPosition.of(0, 5)));
    }

    @Test
    void chebyshevDistance_diagonal() {
        assertEquals(3, ChunkPosition.of(0, 0).chebyshevDistance(ChunkPosition.of(3, 3)));
    }

    @Test
    void chebyshevDistance_negative() {
        assertEquals(4, ChunkPosition.of(0, 0).chebyshevDistance(ChunkPosition.of(-4, 2)));
    }

    @Test
    void chebyshevDistance_self() {
        ChunkPosition pos = ChunkPosition.of(5, 5);
        assertEquals(0, pos.chebyshevDistance(pos));
    }

    @Test
    void isWithinRadius_inside() {
        ChunkPosition centre = ChunkPosition.of(0, 0);
        assertTrue(ChunkPosition.of(2, 2).isWithinRadius(centre, 2));
    }

    @Test
    void isWithinRadius_onBoundary() {
        ChunkPosition centre = ChunkPosition.of(0, 0);
        assertTrue(ChunkPosition.of(3, 0).isWithinRadius(centre, 3));
    }

    @Test
    void isWithinRadius_outside() {
        ChunkPosition centre = ChunkPosition.of(0, 0);
        assertFalse(ChunkPosition.of(4, 0).isWithinRadius(centre, 3));
    }

    // ── toString ──────────────────────────────────────────────────────────

    @Test
    void toString_containsCoordinates() {
        String s = ChunkPosition.of(3, -7).toString();
        assertTrue(s.contains("3"));
        assertTrue(s.contains("-7"));
    }
}
