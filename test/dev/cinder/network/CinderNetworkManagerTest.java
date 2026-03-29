package dev.cinder.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CinderNetworkManager.
 *
 * No live sockets — tests exercise the rate limiter, LongArrayDeque,
 * and VarInt codec via reflection / package-accessible hooks.
 */
class CinderNetworkManagerTest {

    // -------------------------------------------------------------------------
    // LongArrayDeque
    // -------------------------------------------------------------------------

    @Test
    void deque_basicAddPoll() {
        CinderNetworkManager.LongArrayDeque d = new CinderNetworkManager.LongArrayDeque();
        assertTrue(d.isEmpty());
        d.addLast(1L);
        d.addLast(2L);
        d.addLast(3L);
        assertFalse(d.isEmpty());
        assertEquals(1L, d.pollFirst());
        assertEquals(2L, d.peekFirst());
        assertEquals(2L, d.pollFirst());
        assertEquals(3L, d.pollFirst());
        assertTrue(d.isEmpty());
    }

    @Test
    void deque_growsBeyondInitialCapacity() {
        CinderNetworkManager.LongArrayDeque d = new CinderNetworkManager.LongArrayDeque();
        // Initial capacity is 8; push 32 entries to force multiple grows.
        for (long i = 0; i < 32; i++) d.addLast(i);
        assertEquals(32, d.size());
        for (long i = 0; i < 32; i++) {
            assertEquals(i, d.pollFirst());
        }
        assertTrue(d.isEmpty());
    }

    @Test
    void deque_wrapsCorrectlyAfterPoll() {
        CinderNetworkManager.LongArrayDeque d = new CinderNetworkManager.LongArrayDeque();
        // Fill to capacity, drain half, fill again — exercises wrap-around path in grow().
        for (long i = 0; i < 8; i++) d.addLast(i);
        for (int i = 0; i < 4; i++) d.pollFirst();     // head now at index 4
        for (long i = 8; i < 16; i++) d.addLast(i);    // triggers grow with wrapped layout
        assertEquals(12, d.size());
        assertEquals(4L, d.pollFirst());
        assertEquals(5L, d.pollFirst());
    }

    @Test
    void deque_peekDoesNotConsume() {
        CinderNetworkManager.LongArrayDeque d = new CinderNetworkManager.LongArrayDeque();
        d.addLast(42L);
        assertEquals(42L, d.peekFirst());
        assertEquals(42L, d.peekFirst()); // second peek same value
        assertEquals(1, d.size());
    }

    // -------------------------------------------------------------------------
    // VarInt (via CinderConnection which is package-private accessible in test)
    // -------------------------------------------------------------------------

    @Test
    void varInt_roundTrip_singleByte() {
        assertVarIntRoundTrip(0);
        assertVarIntRoundTrip(1);
        assertVarIntRoundTrip(127);
    }

    @Test
    void varInt_roundTrip_multiByte() {
        assertVarIntRoundTrip(128);
        assertVarIntRoundTrip(255);
        assertVarIntRoundTrip(2097151); // max 3-byte VarInt
        assertVarIntRoundTrip(Integer.MAX_VALUE);
    }

    @Test
    void varInt_partialRead_returnsInvalid() {
        // Buffer with only the first byte of a multi-byte VarInt (MSB set, no continuation).
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{(byte) 0x80}); // MSB set, no following byte
        CinderConnection.VarIntResult result = CinderConnection.readVarInt(buf);
        assertFalse(result.valid);
        // Buffer position must be restored on failure.
        assertEquals(0, buf.position());
    }

    @Test
    void varInt_read_doesNotAdvanceOnFailure() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{(byte) 0xFF, (byte) 0xFF}); // incomplete 5-byte VarInt
        int before = buf.position();
        CinderConnection.VarIntResult result = CinderConnection.readVarInt(buf);
        assertFalse(result.valid);
        assertEquals(before, buf.position());
    }

    @Test
    void varInt_readSequential() {
        // Two VarInts packed back to back in one buffer.
        ByteBuffer buf = ByteBuffer.allocate(10);
        writeVarIntTo(buf, 300);
        writeVarIntTo(buf, 1);
        buf.flip();

        CinderConnection.VarIntResult r1 = CinderConnection.readVarInt(buf);
        assertTrue(r1.valid);
        assertEquals(300, r1.value);

        CinderConnection.VarIntResult r2 = CinderConnection.readVarInt(buf);
        assertTrue(r2.valid);
        assertEquals(1, r2.value);
    }

    // -------------------------------------------------------------------------
    // Rate limiter (via reflection — isRateLimited is private)
    // -------------------------------------------------------------------------

    @Test
    void rateLimiter_allowsConnectionsUnderLimit() throws Exception {
        CinderNetworkManager mgr = new CinderNetworkManager(null, "127.0.0.1", 25565);
        Method m = CinderNetworkManager.class.getDeclaredMethod("isRateLimited", String.class);
        m.setAccessible(true);

        // 5 connections from the same IP should all be allowed (limit is 5 per window).
        for (int i = 0; i < 5; i++) {
            boolean limited = (boolean) m.invoke(mgr, "10.0.0.1");
            assertFalse(limited, "Connection " + (i + 1) + " should not be rate-limited");
        }
    }

    @Test
    void rateLimiter_blocksOnceWindowExceeded() throws Exception {
        CinderNetworkManager mgr = new CinderNetworkManager(null, "127.0.0.1", 25565);
        Method m = CinderNetworkManager.class.getDeclaredMethod("isRateLimited", String.class);
        m.setAccessible(true);

        // Fill the window.
        for (int i = 0; i < 5; i++) m.invoke(mgr, "10.0.0.2");

        // 6th connection must be blocked.
        boolean limited = (boolean) m.invoke(mgr, "10.0.0.2");
        assertTrue(limited, "6th connection should be rate-limited");
    }

    @Test
    void rateLimiter_differentIpsAreIndependent() throws Exception {
        CinderNetworkManager mgr = new CinderNetworkManager(null, "127.0.0.1", 25565);
        Method m = CinderNetworkManager.class.getDeclaredMethod("isRateLimited", String.class);
        m.setAccessible(true);

        // Fill window for one IP.
        for (int i = 0; i < 5; i++) m.invoke(mgr, "10.0.0.3");

        // Different IP should still be allowed.
        boolean limited = (boolean) m.invoke(mgr, "10.0.0.4");
        assertFalse(limited, "Different IP should not be rate-limited");
    }

    @Test
    void rateLimiter_pruneRemovesStaleEntries() throws Exception {
        CinderNetworkManager mgr = new CinderNetworkManager(null, "127.0.0.1", 25565);

        Method isRateLimited = CinderNetworkManager.class.getDeclaredMethod("isRateLimited", String.class);
        isRateLimited.setAccessible(true);
        Method prune = CinderNetworkManager.class.getDeclaredMethod("pruneRateLimitTable");
        prune.setAccessible(true);
        Field tableField = CinderNetworkManager.class.getDeclaredField("rateLimitTable");
        tableField.setAccessible(true);

        // Register a connection to populate the table.
        isRateLimited.invoke(mgr, "10.0.0.5");

        @SuppressWarnings("unchecked")
        Map<String, ?> table = (Map<String, ?>) tableField.get(mgr);
        assertFalse(table.isEmpty());

        // Directly clear the deque to simulate window expiry, then prune.
        CinderNetworkManager.LongArrayDeque deque =
                (CinderNetworkManager.LongArrayDeque) table.get("10.0.0.5");
        assertNotNull(deque);
        synchronized (deque) {
            while (!deque.isEmpty()) deque.pollFirst();
        }

        prune.invoke(mgr);
        assertTrue(table.isEmpty(), "Prune should remove stale IP entries");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertVarIntRoundTrip(int value) {
        ByteBuffer buf = ByteBuffer.allocate(5);
        writeVarIntTo(buf, value);
        buf.flip();
        CinderConnection.VarIntResult result = CinderConnection.readVarInt(buf);
        assertTrue(result.valid, "VarInt decode failed for value " + value);
        assertEquals(value, result.value, "VarInt round-trip mismatch for " + value);
    }

    private void writeVarIntTo(ByteBuffer buf, int value) {
        // Mirror of CinderConnection.writeVarInt — duplicated here to avoid
        // depending on a private method. Tests the public readVarInt against
        // a known-correct encoding.
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }
}
