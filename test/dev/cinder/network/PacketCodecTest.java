package dev.cinder.network;

import dev.cinder.chunk.ChunkPosition;
import dev.cinder.entity.PlayerEntity;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PacketCodec} decode/encode and {@link PlayerEntity} chunk view geometry.
 *
 * No live sockets, no scheduler, no chunk IO — all tested against real implementations
 * with controlled ByteBuffer inputs.
 */
class PacketCodecTest {

    // -------------------------------------------------------------------------
    // Decode — ConfirmTeleport
    // -------------------------------------------------------------------------

    @Test
    void decode_confirmTeleport() throws Exception {
        ByteBuffer buf = buildPacket(PacketCodec.SB_CONFIRM_TELEPORT, b -> writeVarInt(b, 42));
        PacketCodec.InboundAction action = PacketCodec.decode(buf);
        assertInstanceOf(PacketCodec.ConfirmTeleportAction.class, action);
        assertEquals(42, ((PacketCodec.ConfirmTeleportAction) action).teleportId());
    }

    // -------------------------------------------------------------------------
    // Decode — KeepAliveResponse
    // -------------------------------------------------------------------------

    @Test
    void decode_keepAliveResponse() throws Exception {
        long id = 0xDEADBEEFCAFEL;
        ByteBuffer buf = buildPacket(PacketCodec.SB_KEEP_ALIVE_RESPONSE, b -> b.putLong(id));
        PacketCodec.InboundAction action = PacketCodec.decode(buf);
        assertInstanceOf(PacketCodec.KeepAliveResponseAction.class, action);
        assertEquals(id, ((PacketCodec.KeepAliveResponseAction) action).id());
    }

    // -------------------------------------------------------------------------
    // Decode — PlayerPosition
    // -------------------------------------------------------------------------

    @Test
    void decode_playerPosition_hasPositionNoRotation() throws Exception {
        ByteBuffer buf = buildPacket(PacketCodec.SB_PLAYER_POSITION, b -> {
            b.putDouble(100.5);
            b.putDouble(64.0);
            b.putDouble(-200.25);
            b.put((byte) 1); // on_ground = true
        });
        PacketCodec.InboundAction action = PacketCodec.decode(buf);
        assertInstanceOf(PacketCodec.PlayerMoveAction.class, action);
        PacketCodec.PlayerMoveAction move = (PacketCodec.PlayerMoveAction) action;
        assertEquals(100.5,   move.x(),   1e-9);
        assertEquals(64.0,    move.y(),   1e-9);
        assertEquals(-200.25, move.z(),   1e-9);
        assertTrue(move.onGround());
        assertTrue(move.hasPosition());
        assertFalse(move.hasRotation());
    }

    // -------------------------------------------------------------------------
    // Decode — PlayerPosRot
    // -------------------------------------------------------------------------

    @Test
    void decode_playerPosRot() throws Exception {
        ByteBuffer buf = buildPacket(PacketCodec.SB_PLAYER_POS_ROT, b -> {
            b.putDouble(1.0);
            b.putDouble(2.0);
            b.putDouble(3.0);
            b.putFloat(90.0f);
            b.putFloat(-45.0f);
            b.put((byte) 0); // on_ground = false
        });
        PacketCodec.InboundAction action = PacketCodec.decode(buf);
        assertInstanceOf(PacketCodec.PlayerMoveAction.class, action);
        PacketCodec.PlayerMoveAction move = (PacketCodec.PlayerMoveAction) action;
        assertEquals(90.0f,  move.yaw(),   1e-4f);
        assertEquals(-45.0f, move.pitch(), 1e-4f);
        assertTrue(move.hasPosition());
        assertTrue(move.hasRotation());
        assertFalse(move.onGround());
    }

    // -------------------------------------------------------------------------
    // Decode — PlayerRotation
    // -------------------------------------------------------------------------

    @Test
    void decode_playerRotation_noPosition() throws Exception {
        ByteBuffer buf = buildPacket(PacketCodec.SB_PLAYER_ROTATION, b -> {
            b.putFloat(180.0f);
            b.putFloat(30.0f);
            b.put((byte) 1);
        });
        PacketCodec.InboundAction action = PacketCodec.decode(buf);
        PacketCodec.PlayerMoveAction move = (PacketCodec.PlayerMoveAction) action;
        assertFalse(move.hasPosition());
        assertTrue(move.hasRotation());
        assertEquals(180.0f, move.yaw(), 1e-4f);
    }

    // -------------------------------------------------------------------------
    // Decode — PlayerOnGround
    // -------------------------------------------------------------------------

    @Test
    void decode_playerOnGround() throws Exception {
        ByteBuffer buf = buildPacket(PacketCodec.SB_PLAYER_ON_GROUND, b -> b.put((byte) 1));
        PacketCodec.PlayerMoveAction move = (PacketCodec.PlayerMoveAction) PacketCodec.decode(buf);
        assertFalse(move.hasPosition());
        assertFalse(move.hasRotation());
        assertTrue(move.onGround());
    }

    // -------------------------------------------------------------------------
    // Decode — unknown packet yields UnknownAction
    // -------------------------------------------------------------------------

    @Test
    void decode_unknownPacketId_yieldsUnknownAction() throws Exception {
        ByteBuffer buf = buildPacket(0x7F, b -> {}); // 0x7F not in table
        PacketCodec.InboundAction action = PacketCodec.decode(buf);
        assertInstanceOf(PacketCodec.UnknownAction.class, action);
        assertEquals(0x7F, ((PacketCodec.UnknownAction) action).packetId());
    }

    // -------------------------------------------------------------------------
    // Decode — truncated buffer throws DecodeException
    // -------------------------------------------------------------------------

    @Test
    void decode_truncatedKeepAlive_throwsDecodeException() {
        // Keep alive response expects 8 bytes for the ID; give it only 4.
        ByteBuffer buf = buildPacket(PacketCodec.SB_KEEP_ALIVE_RESPONSE, b -> b.putInt(0));
        assertThrows(PacketCodec.DecodeException.class, () -> PacketCodec.decode(buf));
    }

    @Test
    void decode_truncatedPosition_throwsDecodeException() {
        // Player position expects 25 bytes; give it 10.
        ByteBuffer buf = buildPacket(PacketCodec.SB_PLAYER_POSITION, b -> b.putLong(0).putShort((short) 0));
        assertThrows(PacketCodec.DecodeException.class, () -> PacketCodec.decode(buf));
    }

    // -------------------------------------------------------------------------
    // Encode — frame structure
    // -------------------------------------------------------------------------

    @Test
    void encode_keepAlive_correctlyFramed() {
        long id = 12345L;
        ByteBuffer framed = PacketCodec.encodeKeepAlive(id);
        // First byte(s) are the length VarInt.
        int length = readVarInt(framed);
        // Length field covers: packet_id VarInt (1 byte for 0x26) + long (8 bytes) = 9 bytes.
        assertEquals(9, length);
        int packetId = readVarInt(framed);
        assertEquals(PacketCodec.CB_KEEP_ALIVE, packetId);
        assertEquals(id, framed.getLong());
        assertFalse(framed.hasRemaining());
    }

    @Test
    void encode_setCenterChunk_correctValues() {
        ByteBuffer framed = PacketCodec.encodeSetCenterChunk(-5, 12);
        readVarInt(framed); // skip length
        int packetId = readVarInt(framed);
        assertEquals(PacketCodec.CB_SET_CENTER_CHUNK, packetId);
        int chunkX = readVarInt(framed);
        int chunkZ = readVarInt(framed);
        assertEquals(-5, chunkX);
        assertEquals(12, chunkZ);
    }

    @Test
    void encode_setRenderDistance() {
        ByteBuffer framed = PacketCodec.encodeSetRenderDistance(10);
        readVarInt(framed); // skip length
        assertEquals(PacketCodec.CB_SET_RENDER_DISTANCE, readVarInt(framed));
        assertEquals(10, readVarInt(framed));
    }

    @Test
    void encode_playerPositionSync_allFields() {
        ByteBuffer framed = PacketCodec.encodePlayerPositionSync(1.0, 64.0, -1.0, 90.0f, 0.0f, 7);
        readVarInt(framed); // length
        assertEquals(PacketCodec.CB_PLAYER_POSITION_SYNC, readVarInt(framed));
        assertEquals(7, readVarInt(framed));      // teleport id
        assertEquals(1.0,   framed.getDouble(), 1e-9);
        assertEquals(64.0,  framed.getDouble(), 1e-9);
        assertEquals(-1.0,  framed.getDouble(), 1e-9);
        // velocity x/y/z
        framed.getDouble(); framed.getDouble(); framed.getDouble();
        assertEquals(90.0f, framed.getFloat(), 1e-4f);
        assertEquals(0.0f,  framed.getFloat(), 1e-4f);
        assertEquals(0, framed.getInt()); // flags = 0 (absolute)
    }

    // -------------------------------------------------------------------------
    // PlayerEntity — chunk view geometry (no scheduler/chunk IO needed)
    // -------------------------------------------------------------------------

    @Test
    void chunksInView_radius0_containsOnlyCenter() {
        ChunkPosition center = ChunkPosition.of(5, -3);
        Set<ChunkPosition> view = PlayerEntity.chunksInView(center, 0);
        assertEquals(1, view.size());
        assertTrue(view.contains(center));
    }

    @Test
    void chunksInView_radius1_contains9Chunks() {
        ChunkPosition center = ChunkPosition.of(0, 0);
        Set<ChunkPosition> view = PlayerEntity.chunksInView(center, 1);
        assertEquals(9, view.size());
        // All 9 offsets must be present.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertTrue(view.contains(center.offset(dx, dz)),
                        "Missing chunk at offset (" + dx + "," + dz + ")");
            }
        }
    }

    @Test
    void chunksInView_radius8_contains289Chunks() {
        ChunkPosition center = ChunkPosition.of(0, 0);
        Set<ChunkPosition> view = PlayerEntity.chunksInView(center, 8);
        assertEquals(17 * 17, view.size());
    }

    @Test
    void chunksInView_negativeCoordinates() {
        // Negative chunk coords must work — ChunkPosition handles them.
        ChunkPosition center = ChunkPosition.of(-10, -10);
        Set<ChunkPosition> view = PlayerEntity.chunksInView(center, 2);
        assertEquals(25, view.size());
        assertTrue(view.contains(ChunkPosition.of(-12, -12)));
        assertTrue(view.contains(ChunkPosition.of(-8, -8)));
    }

    @Test
    void chunksInView_largeRadius_noOverflow() {
        // viewDistance=32 is the max allowed; verify no exception and correct count.
        ChunkPosition center = ChunkPosition.of(0, 0);
        Set<ChunkPosition> view = PlayerEntity.chunksInView(center, 32);
        assertEquals(65 * 65, view.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a packet buffer: write the packet ID VarInt then let the consumer add fields. */
    private ByteBuffer buildPacket(int packetId, java.util.function.Consumer<ByteBuffer> fields) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        writeVarInt(buf, packetId);
        fields.accept(buf);
        buf.flip();
        return buf;
    }

    private void writeVarInt(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    private int readVarInt(ByteBuffer buf) {
        int value = 0, shift = 0;
        byte b;
        do {
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}
