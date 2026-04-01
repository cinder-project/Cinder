package dev.cinder.network;

import dev.cinder.chunk.CinderChunk;
import dev.cinder.chunk.ChunkPosition;
import dev.cinder.world.FlatWorldGenerator;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDataPacketBuilderTest {

    @Test
    void buildChunkDataAndLight_writesExpectedEnvelope() {
        FlatWorldGenerator generator = new FlatWorldGenerator();
        CinderChunk chunk = generator.generate(ChunkPosition.of(2, -3));

        ByteBuffer packet = ChunkDataPacketBuilder.buildChunkDataAndLight(chunk);
        int frameLen = readVarInt(packet);
        assertEquals(frameLen, packet.remaining());

        int packetId = readVarInt(packet);
        assertEquals(ChunkDataPacketBuilder.CB_CHUNK_DATA_AND_LIGHT, packetId);
        assertEquals(2, packet.getInt());
        assertEquals(-3, packet.getInt());

        skipNbtCompound(packet);

        int chunkDataLength = readVarInt(packet);
        assertTrue(chunkDataLength > 0);
        packet.position(packet.position() + chunkDataLength);

        int blockEntityCount = readVarInt(packet);
        assertEquals(0, blockEntityCount);

        long fullMask = (1L << (CinderChunk.SECTION_COUNT + 2)) - 1L;

        assertEquals(1, readVarInt(packet));
        assertEquals(fullMask, packet.getLong());

        assertEquals(1, readVarInt(packet));
        assertEquals(0L, packet.getLong());

        assertEquals(1, readVarInt(packet));
        assertEquals(0L, packet.getLong());

        assertEquals(1, readVarInt(packet));
        assertEquals(fullMask, packet.getLong());

        int skyLightArrays = readVarInt(packet);
        assertEquals(CinderChunk.SECTION_COUNT + 2, skyLightArrays);
        for (int i = 0; i < skyLightArrays; i++) {
            int sectionLen = readVarInt(packet);
            assertEquals(2048, sectionLen);
            packet.position(packet.position() + sectionLen);
        }

        int blockLightArrays = readVarInt(packet);
        assertEquals(0, blockLightArrays);
        assertEquals(0, packet.remaining());
    }

    @Test
    void build_aliasReturnsEquivalentPacketBytes() {
        FlatWorldGenerator generator = new FlatWorldGenerator();
        CinderChunk chunk = generator.generate(ChunkPosition.of(0, 0));

        byte[] direct = copy(ChunkDataPacketBuilder.buildChunkDataAndLight(chunk));
        byte[] alias = copy(ChunkDataPacketBuilder.build(chunk));

        assertEquals(direct.length, alias.length);
        for (int i = 0; i < direct.length; i++) {
            assertEquals(direct[i], alias[i], "byte mismatch at index " + i);
        }
    }

    private static byte[] copy(ByteBuffer buf) {
        ByteBuffer dup = buf.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static int readVarInt(ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = buf.get();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalArgumentException("VarInt too long");
            }
        }
    }

    private static void skipNbtCompound(ByteBuffer buf) {
        byte rootType = buf.get();
        if (rootType != 10) {
            throw new IllegalArgumentException("Expected TAG_Compound root");
        }

        int rootNameLength = Short.toUnsignedInt(buf.getShort());
        buf.position(buf.position() + rootNameLength);

        while (true) {
            int tagType = Byte.toUnsignedInt(buf.get());
            if (tagType == 0) {
                return;
            }

            int nameLen = Short.toUnsignedInt(buf.getShort());
            buf.position(buf.position() + nameLen);

            if (tagType == 12) {
                int longCount = buf.getInt();
                buf.position(buf.position() + longCount * Long.BYTES);
                continue;
            }

            throw new IllegalArgumentException("Unexpected tag type: " + tagType);
        }
    }
}