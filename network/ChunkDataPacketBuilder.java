package dev.cinder.network;

import dev.cinder.chunk.CinderChunk;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Builds a clientbound chunk data + light update packet from a {@link CinderChunk}.
 *
 * Packet shape follows the modern map-chunk-with-light layout:
 *   - chunk coordinates
 *   - heightmaps NBT
 *   - chunk section data (block states + biomes)
 *   - block entities (empty for now)
 *   - sky/block light masks and light arrays
 */
public final class ChunkDataPacketBuilder {

    /** Clientbound Chunk Data + Light packet ID used by Cinder's protocol table. */
    public static final int CB_CHUNK_DATA_AND_LIGHT = 0x36;

    private static final int SECTION_EDGE = 16;
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final int BIOMES_PER_SECTION = 4 * 4 * 4;
    private static final int SKY_LIGHT_BYTES_PER_SECTION = BLOCKS_PER_SECTION / 2;
    private static final int LIGHT_SECTION_COUNT = CinderChunk.SECTION_COUNT + 2;

    private static final int AIR_STATE_ID = 0;
    private static final int DEFAULT_BIOME_ID = 1; // plains in vanilla biome registry

    private static final int HEIGHTMAP_BITS = 9;
    private static final int HEIGHTMAP_COLUMNS = 16 * 16;

    private static final long FULL_LIGHT_MASK = (1L << LIGHT_SECTION_COUNT) - 1L;
    private static final byte[] FULL_BRIGHT_LIGHT_SECTION = createFullBrightLightSection();

    private ChunkDataPacketBuilder() {
    }

    /**
     * Build a framed packet ready for {@link CinderConnection#enqueuePacket(ByteBuffer)}.
     */
    public static ByteBuffer buildChunkDataAndLight(CinderChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        byte[] chunkData = buildChunkSectionData(chunk);
        byte[] heightmapsNbt = buildHeightmapsNbt(chunk);

        BufferWriter payload = new BufferWriter(4096 + chunkData.length);
        payload.writeVarInt(CB_CHUNK_DATA_AND_LIGHT);
        payload.writeInt(chunk.position.x);
        payload.writeInt(chunk.position.z);

        payload.writeBytes(heightmapsNbt);

        payload.writeVarInt(chunkData.length);
        payload.writeBytes(chunkData);

        // Block entities are not emitted in Phase 3 flat terrain.
        payload.writeVarInt(0);

        // Light masks (BitSet encoded as VarInt-length long arrays).
        writeLongArray(payload, FULL_LIGHT_MASK); // skyLightMask
        writeLongArray(payload, 0L);             // blockLightMask
        writeLongArray(payload, 0L);             // emptySkyLightMask
        writeLongArray(payload, FULL_LIGHT_MASK); // emptyBlockLightMask

        // One full-bright sky-light array per light section.
        payload.writeVarInt(LIGHT_SECTION_COUNT);
        for (int i = 0; i < LIGHT_SECTION_COUNT; i++) {
            payload.writeVarInt(FULL_BRIGHT_LIGHT_SECTION.length);
            payload.writeBytes(FULL_BRIGHT_LIGHT_SECTION);
        }

        // No explicit block light arrays (all empty via emptyBlockLightMask).
        payload.writeVarInt(0);

        ByteBuffer packetPayload = ByteBuffer.wrap(payload.toByteArray());
        return PacketCodec.frame(packetPayload);
    }

    /** Convenience alias for call sites that prefer concise naming. */
    public static ByteBuffer build(CinderChunk chunk) {
        return buildChunkDataAndLight(chunk);
    }

    private static byte[] buildChunkSectionData(CinderChunk chunk) {
        BufferWriter out = new BufferWriter(CinderChunk.SECTION_COUNT * 1024);

        int chunkMinX = chunk.position.blockMinX();
        int chunkMinZ = chunk.position.blockMinZ();

        for (int sectionIndex = 0; sectionIndex < CinderChunk.SECTION_COUNT; sectionIndex++) {
            int sectionMinY = CinderChunk.WORLD_MIN_Y + (sectionIndex * SECTION_EDGE);

            int[] blockStates = new int[BLOCKS_PER_SECTION];
            int nonAirCount = 0;

            int idx = 0;
            for (int localY = 0; localY < SECTION_EDGE; localY++) {
                int worldY = sectionMinY + localY;
                for (int localZ = 0; localZ < SECTION_EDGE; localZ++) {
                    int worldZ = chunkMinZ + localZ;
                    for (int localX = 0; localX < SECTION_EDGE; localX++) {
                        int worldX = chunkMinX + localX;
                        int stateId = Short.toUnsignedInt(chunk.getBlock(worldX, worldY, worldZ));
                        blockStates[idx++] = stateId;
                        if (stateId != AIR_STATE_ID) {
                            nonAirCount++;
                        }
                    }
                }
            }

            out.writeShort(nonAirCount);
            writePalettedContainer(out, blockStates, 4, 15);

            int[] biomes = new int[BIOMES_PER_SECTION];
            Arrays.fill(biomes, DEFAULT_BIOME_ID);
            writePalettedContainer(out, biomes, 1, 6);
        }

        return out.toByteArray();
    }

    private static void writePalettedContainer(BufferWriter out, int[] values, int minPaletteBits, int directBits) {
        int[] palette = new int[16];
        int[] indices = new int[values.length];
        int paletteSize = 0;

        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            int paletteIndex = indexOf(palette, paletteSize, value);
            if (paletteIndex < 0) {
                if (paletteSize == palette.length) {
                    palette = Arrays.copyOf(palette, palette.length * 2);
                }
                paletteIndex = paletteSize;
                palette[paletteSize++] = value;
            }
            indices[i] = paletteIndex;
        }

        if (paletteSize == 1) {
            out.writeByte(0);
            out.writeVarInt(palette[0]);
            out.writeVarInt(0);
            return;
        }

        int paletteBits = Math.max(minPaletteBits, bitsRequired(paletteSize));
        if (paletteBits <= 8) {
            out.writeByte(paletteBits);
            out.writeVarInt(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                out.writeVarInt(palette[i]);
            }

            long[] data = packValues(indices, paletteBits);
            out.writeVarInt(data.length);
            for (long l : data) {
                out.writeLong(l);
            }
            return;
        }

        out.writeByte(directBits);
        long[] data = packValues(values, directBits);
        out.writeVarInt(data.length);
        for (long l : data) {
            out.writeLong(l);
        }
    }

    private static int bitsRequired(int values) {
        if (values <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(values - 1);
    }

    private static long[] packValues(int[] values, int bitsPerEntry) {
        long[] packed = new long[(values.length * bitsPerEntry + 63) >>> 6];
        long mask = (1L << bitsPerEntry) - 1L;

        for (int i = 0; i < values.length; i++) {
            long v = values[i] & mask;
            int bitIndex = i * bitsPerEntry;
            int startLong = bitIndex >>> 6;
            int startOffset = bitIndex & 63;

            packed[startLong] |= v << startOffset;

            int spill = startOffset + bitsPerEntry - 64;
            if (spill > 0) {
                packed[startLong + 1] |= v >>> (bitsPerEntry - spill);
            }
        }

        return packed;
    }

    private static byte[] buildHeightmapsNbt(CinderChunk chunk) {
        int chunkMinX = chunk.position.blockMinX();
        int chunkMinZ = chunk.position.blockMinZ();

        int[] heights = new int[HEIGHTMAP_COLUMNS];
        for (int localZ = 0; localZ < 16; localZ++) {
            int worldZ = chunkMinZ + localZ;
            for (int localX = 0; localX < 16; localX++) {
                int worldX = chunkMinX + localX;
                int encodedHeight = 0; // all-air column

                for (int y = CinderChunk.WORLD_MAX_Y; y >= CinderChunk.WORLD_MIN_Y; y--) {
                    if (Short.toUnsignedInt(chunk.getBlock(worldX, y, worldZ)) != AIR_STATE_ID) {
                        encodedHeight = (y + 1) - CinderChunk.WORLD_MIN_Y;
                        break;
                    }
                }

                heights[(localZ << 4) | localX] = encodedHeight;
            }
        }

        long[] packedHeights = packValues(heights, HEIGHTMAP_BITS);

        BufferWriter nbt = new BufferWriter(512);
        nbt.writeByte(10); // TAG_Compound
        nbt.writeShort(0); // root name length = 0

        writeLongArrayTag(nbt, "MOTION_BLOCKING", packedHeights);
        writeLongArrayTag(nbt, "WORLD_SURFACE", packedHeights);

        nbt.writeByte(0); // TAG_End
        return nbt.toByteArray();
    }

    private static void writeLongArrayTag(BufferWriter nbt, String name, long[] value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        nbt.writeByte(12); // TAG_Long_Array
        nbt.writeShort(nameBytes.length);
        nbt.writeBytes(nameBytes);
        nbt.writeInt(value.length);
        for (long l : value) {
            nbt.writeLong(l);
        }
    }

    private static void writeLongArray(BufferWriter out, long... values) {
        out.writeVarInt(values.length);
        for (long value : values) {
            out.writeLong(value);
        }
    }

    private static int indexOf(int[] values, int size, int target) {
        for (int i = 0; i < size; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] createFullBrightLightSection() {
        byte[] bytes = new byte[SKY_LIGHT_BYTES_PER_SECTION];
        Arrays.fill(bytes, (byte) 0xFF);
        return bytes;
    }

    private static final class BufferWriter {
        private final ByteArrayOutputStream out;

        BufferWriter(int initialCapacity) {
            this.out = new ByteArrayOutputStream(initialCapacity);
        }

        void writeByte(int value) {
            out.write(value & 0xFF);
        }

        void writeShort(int value) {
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        void writeInt(int value) {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        void writeLong(long value) {
            out.write((int) ((value >>> 56) & 0xFF));
            out.write((int) ((value >>> 48) & 0xFF));
            out.write((int) ((value >>> 40) & 0xFF));
            out.write((int) ((value >>> 32) & 0xFF));
            out.write((int) ((value >>> 24) & 0xFF));
            out.write((int) ((value >>> 16) & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) (value & 0xFF));
        }

        void writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            writeByte(value);
        }

        void writeBytes(byte[] bytes) {
            out.write(bytes, 0, bytes.length);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}