package dev.cinder.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Stateless packet codec for the Minecraft Java Edition protocol (1.21.x, protocol 769).
 *
 * <p>Decodes inbound serverbound packets into structured {@link InboundAction} records.
 * Encodes outbound clientbound packets into framed (length-prefixed) {@link ByteBuffer}
 * instances ready for {@link CinderConnection#enqueuePacket}.
 *
 * <p>All methods are static. No instances, no state.
 *
 * <p>Serverbound play packet IDs decoded here:
 * <pre>
 *   0x00  Confirm Teleportation
 *   0x06  Chat Message
 *   0x18  Player Command        (sprint/sneak state)
 *   0x1A  Keep Alive Response
 *   0x1B  Player Position
 *   0x1C  Player Position and Rotation
 *   0x1D  Player Rotation
 *   0x1E  Player On Ground
 * </pre>
 *
 * <p>Clientbound play packets encoded here:
 * <pre>
 *   0x26  Keep Alive
 *   0x28  Login (Play)
 *   0x40  Synchronize Player Position
 *   0x5E  Set Center Chunk
 *   0x61  Set Render Distance
 * </pre>
 *
 * <p>Packet IDs not in the table above yield {@link UnknownAction} on decode
 * and are silently ignored by the dispatcher in {@link dev.cinder.entity.PlayerEntity}.
 */
public final class PacketCodec {

    private PacketCodec() {}

    // -------------------------------------------------------------------------
    // Protocol constant
    // -------------------------------------------------------------------------

    public static final int PROTOCOL_VERSION = 769;

    // -------------------------------------------------------------------------
    // Serverbound packet IDs
    // -------------------------------------------------------------------------

    public static final int SB_CONFIRM_TELEPORT    = 0x00;
    public static final int SB_CHAT_MESSAGE        = 0x06;
    public static final int SB_PLAYER_COMMAND      = 0x18;
    public static final int SB_KEEP_ALIVE_RESPONSE = 0x1A;
    public static final int SB_PLAYER_POSITION     = 0x1B;
    public static final int SB_PLAYER_POS_ROT      = 0x1C;
    public static final int SB_PLAYER_ROTATION     = 0x1D;
    public static final int SB_PLAYER_ON_GROUND    = 0x1E;

    // -------------------------------------------------------------------------
    // Clientbound packet IDs
    // -------------------------------------------------------------------------

    public static final int CB_KEEP_ALIVE           = 0x26;
    public static final int CB_LOGIN                = 0x28;
    public static final int CB_PLAYER_POSITION_SYNC = 0x40;
    public static final int CB_SET_CENTER_CHUNK     = 0x5E;
    public static final int CB_SET_RENDER_DISTANCE  = 0x61;

    // -------------------------------------------------------------------------
    // Inbound action sealed hierarchy
    // -------------------------------------------------------------------------

    /** Marker interface for all decoded inbound actions. */
    public sealed interface InboundAction permits
            ConfirmTeleportAction,
            ChatMessageAction,
            PlayerCommandAction,
            KeepAliveResponseAction,
            PlayerMoveAction,
            UnknownAction {}

    /** Client acknowledges a teleport with the teleport ID it was given. */
    public record ConfirmTeleportAction(int teleportId) implements InboundAction {}

    /** Chat message sent by the player. */
    public record ChatMessageAction(String message) implements InboundAction {}

    /** Sprint or sneak state change. */
    public record PlayerCommandAction(int entityId, PlayerCommandAction.Command command) implements InboundAction {
        public enum Command {
            START_SNEAKING, STOP_SNEAKING, LEAVE_BED,
            START_SPRINTING, STOP_SPRINTING,
            START_JUMP_WITH_HORSE, STOP_JUMP_WITH_HORSE,
            OPEN_HORSE_INVENTORY, START_FLYING_WITH_ELYTRA,
            UNKNOWN
        }
    }

    /** Client acknowledges a server keep-alive ping. */
    public record KeepAliveResponseAction(long id) implements InboundAction {}

    /**
     * Any movement packet (position, position+rotation, rotation, on-ground).
     * Fields not present in the specific packet variant retain their previous values —
     * callers must apply deltas selectively using {@link #hasPosition} and {@link #hasRotation}.
     */
    public record PlayerMoveAction(
            double x,
            double y,
            double z,
            float  yaw,
            float  pitch,
            boolean onGround,
            boolean hasPosition,
            boolean hasRotation
    ) implements InboundAction {}

    /** Packet ID not in the decode table. Dispatcher ignores these. */
    public record UnknownAction(int packetId) implements InboundAction {}

    // -------------------------------------------------------------------------
    // Decode entry point
    // -------------------------------------------------------------------------

    /**
     * Decode one serverbound play-phase packet from {@code payload}.
     *
     * <p>The buffer must be positioned at the first byte of the packet (the packet ID VarInt).
     * The entire packet payload must be present — no partial reads.
     *
     * @param payload buffer containing exactly one framed packet's content
     * @return decoded action; never null
     * @throws DecodeException if the packet is structurally malformed
     */
    public static InboundAction decode(ByteBuffer payload) throws DecodeException {
        int packetId = requireVarInt(payload, "packet id");

        return switch (packetId) {
            case SB_CONFIRM_TELEPORT    -> decodeConfirmTeleport(payload);
            case SB_CHAT_MESSAGE        -> decodeChatMessage(payload);
            case SB_PLAYER_COMMAND      -> decodePlayerCommand(payload);
            case SB_KEEP_ALIVE_RESPONSE -> decodeKeepAliveResponse(payload);
            case SB_PLAYER_POSITION     -> decodePlayerPosition(payload);
            case SB_PLAYER_POS_ROT      -> decodePlayerPosRot(payload);
            case SB_PLAYER_ROTATION     -> decodePlayerRotation(payload);
            case SB_PLAYER_ON_GROUND    -> decodePlayerOnGround(payload);
            default                     -> new UnknownAction(packetId);
        };
    }

    // -------------------------------------------------------------------------
    // Serverbound decoders
    // -------------------------------------------------------------------------

    private static ConfirmTeleportAction decodeConfirmTeleport(ByteBuffer buf) throws DecodeException {
        int teleportId = requireVarInt(buf, "teleport id");
        return new ConfirmTeleportAction(teleportId);
    }

    private static ChatMessageAction decodeChatMessage(ByteBuffer buf) throws DecodeException {
        // Fields: message (String ≤256), timestamp (long), salt (long),
        //         signature present (bool), [signature bytes], message count (VarInt),
        //         acknowledged (BitSet, 20 fixed bytes)
        // We only care about the message content.
        String message = requireString(buf, 256, "chat message");
        // Skip remaining fields — timestamp, salt, signature, acknowledged bitmap.
        // Safe to ignore; we don't do chat signing validation in Phase 2.
        return new ChatMessageAction(message);
    }

    private static PlayerCommandAction decodePlayerCommand(ByteBuffer buf) throws DecodeException {
        int entityId   = requireVarInt(buf, "entity id");
        int actionId   = requireVarInt(buf, "action id");
        requireVarInt(buf, "jump boost"); // horse jump power — unused

        PlayerCommandAction.Command cmd = switch (actionId) {
            case 0 -> PlayerCommandAction.Command.START_SNEAKING;
            case 1 -> PlayerCommandAction.Command.STOP_SNEAKING;
            case 2 -> PlayerCommandAction.Command.LEAVE_BED;
            case 3 -> PlayerCommandAction.Command.START_SPRINTING;
            case 4 -> PlayerCommandAction.Command.STOP_SPRINTING;
            case 5 -> PlayerCommandAction.Command.START_JUMP_WITH_HORSE;
            case 6 -> PlayerCommandAction.Command.STOP_JUMP_WITH_HORSE;
            case 7 -> PlayerCommandAction.Command.OPEN_HORSE_INVENTORY;
            case 8 -> PlayerCommandAction.Command.START_FLYING_WITH_ELYTRA;
            default -> PlayerCommandAction.Command.UNKNOWN;
        };
        return new PlayerCommandAction(entityId, cmd);
    }

    private static KeepAliveResponseAction decodeKeepAliveResponse(ByteBuffer buf) throws DecodeException {
        if (buf.remaining() < 8) throw new DecodeException("keep alive response: expected 8 bytes, got " + buf.remaining());
        long id = buf.getLong();
        return new KeepAliveResponseAction(id);
    }

    private static PlayerMoveAction decodePlayerPosition(ByteBuffer buf) throws DecodeException {
        // x (double), y (double), z (double), on_ground (boolean)
        requireBytes(buf, 25, "player position");
        double x  = buf.getDouble();
        double y  = buf.getDouble();
        double z  = buf.getDouble();
        boolean onGround = buf.get() != 0;
        return new PlayerMoveAction(x, y, z, 0f, 0f, onGround, true, false);
    }

    private static PlayerMoveAction decodePlayerPosRot(ByteBuffer buf) throws DecodeException {
        // x (double), y (double), z (double), yaw (float), pitch (float), on_ground (boolean)
        requireBytes(buf, 33, "player position+rotation");
        double  x        = buf.getDouble();
        double  y        = buf.getDouble();
        double  z        = buf.getDouble();
        float   yaw      = buf.getFloat();
        float   pitch    = buf.getFloat();
        boolean onGround = buf.get() != 0;
        return new PlayerMoveAction(x, y, z, yaw, pitch, onGround, true, true);
    }

    private static PlayerMoveAction decodePlayerRotation(ByteBuffer buf) throws DecodeException {
        // yaw (float), pitch (float), on_ground (boolean)
        requireBytes(buf, 9, "player rotation");
        float   yaw      = buf.getFloat();
        float   pitch    = buf.getFloat();
        boolean onGround = buf.get() != 0;
        return new PlayerMoveAction(0, 0, 0, yaw, pitch, onGround, false, true);
    }

    private static PlayerMoveAction decodePlayerOnGround(ByteBuffer buf) throws DecodeException {
        requireBytes(buf, 1, "player on ground");
        boolean onGround = buf.get() != 0;
        return new PlayerMoveAction(0, 0, 0, 0f, 0f, onGround, false, false);
    }

    // -------------------------------------------------------------------------
    // Clientbound encoders
    // -------------------------------------------------------------------------

    /**
     * Encode a Keep Alive packet (CB 0x26).
     * The client must echo the ID back within 30 seconds or be disconnected.
     */
    public static ByteBuffer encodeKeepAlive(long id) {
        // payload: packet_id (VarInt=1 byte for 0x26) + long (8 bytes) = 9 bytes
        ByteBuffer payload = ByteBuffer.allocate(9);
        writeVarInt(payload, CB_KEEP_ALIVE);
        payload.putLong(id);
        payload.flip();
        return frame(payload);
    }

    /**
     * Encode a Login (Play) packet (CB 0x28).
     * Sent once after Login Success to fully transition the client into play state.
     *
     * @param entityId        the player's entity ID on this server
     * @param isHardcore      whether the world is hardcore
     * @param viewDistance    server view distance in chunks
     * @param simulationDistance server simulation distance in chunks
     */
    public static ByteBuffer encodeLoginPlay(
            int entityId, boolean isHardcore, int viewDistance, int simulationDistance) {

        // Minimal Login (Play) — enough to get the client to accept a world.
        // Full field list per 1.21.x:
        //   entity_id (int), is_hardcore (bool),
        //   dimension_count (VarInt), dimension_names (Identifier[]),
        //   max_players (VarInt), view_distance (VarInt),
        //   simulation_distance (VarInt), reduced_debug (bool),
        //   respawn_screen (bool), do_limited_crafting (bool),
        //   dimension_type (VarInt), dimension_name (Identifier),
        //   hashed_seed (long), game_mode (ubyte), prev_game_mode (byte),
        //   is_debug (bool), is_flat (bool), death_location present (bool),
        //   portal_cooldown (VarInt), sea_level (VarInt), enforce_chat (bool)

        byte[] overworld = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);
        byte[] dimType   = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);

        ByteBuffer payload = ByteBuffer.allocate(256);
        writeVarInt(payload, CB_LOGIN);

        payload.putInt(entityId);                              // entity_id
        payload.put(isHardcore ? (byte) 1 : (byte) 0);        // is_hardcore

        writeVarInt(payload, 1);                               // dimension_count = 1
        writeVarInt(payload, overworld.length);                // dimension name length
        payload.put(overworld);                                // "minecraft:overworld"

        writeVarInt(payload, 100);                             // max_players (ignored by client)
        writeVarInt(payload, viewDistance);
        writeVarInt(payload, simulationDistance);
        payload.put((byte) 0);                                 // reduced_debug_info = false
        payload.put((byte) 1);                                 // enable_respawn_screen = true
        payload.put((byte) 0);                                 // do_limited_crafting = false

        writeVarInt(payload, 0);                               // dimension_type index = 0
        writeVarInt(payload, overworld.length);                // dimension_name length
        payload.put(overworld);                                // dimension name

        payload.putLong(0L);                                   // hashed_seed
        payload.put((byte) 0);                                 // game_mode = survival
        payload.put((byte) -1);                                // prev_game_mode = undefined

        payload.put((byte) 0);                                 // is_debug = false
        payload.put((byte) 0);                                 // is_flat = false
        payload.put((byte) 0);                                 // death_location present = false
        writeVarInt(payload, 0);                               // portal_cooldown = 0
        writeVarInt(payload, 64);                              // sea_level
        payload.put((byte) 0);                                 // enforce_secure_chat = false

        payload.flip();
        return frame(payload);
    }

    /**
     * Encode a Synchronize Player Position packet (CB 0x40).
     * Forces the client to teleport to the given coordinates.
     *
     * @param teleportId monotonically increasing ID; client must confirm via SB 0x00
     */
    public static ByteBuffer encodePlayerPositionSync(
            double x, double y, double z, float yaw, float pitch, int teleportId) {

        // Fields: x(d) y(d) z(d) vel_x(d) vel_y(d) vel_z(d) yaw(f) pitch(f) flags(int) teleport_id(VarInt)
        // flags=0 means all values are absolute.
        ByteBuffer payload = ByteBuffer.allocate(64);
        writeVarInt(payload, CB_PLAYER_POSITION_SYNC);
        writeVarInt(payload, teleportId);
        payload.putDouble(x);
        payload.putDouble(y);
        payload.putDouble(z);
        payload.putDouble(0.0); // velocity x
        payload.putDouble(0.0); // velocity y
        payload.putDouble(0.0); // velocity z
        payload.putFloat(yaw);
        payload.putFloat(pitch);
        payload.putInt(0);      // flags = 0 (all absolute)
        payload.flip();
        return frame(payload);
    }

    /**
     * Encode a Set Center Chunk packet (CB 0x5E).
     * Tells the client which chunk is the center of its view — must be sent
     * whenever the player crosses a chunk boundary.
     */
    public static ByteBuffer encodeSetCenterChunk(int chunkX, int chunkZ) {
        ByteBuffer payload = ByteBuffer.allocate(12);
        writeVarInt(payload, CB_SET_CENTER_CHUNK);
        writeVarInt(payload, chunkX);
        writeVarInt(payload, chunkZ);
        payload.flip();
        return frame(payload);
    }

    /**
     * Encode a Set Render Distance packet (CB 0x61).
     */
    public static ByteBuffer encodeSetRenderDistance(int viewDistance) {
        ByteBuffer payload = ByteBuffer.allocate(6);
        writeVarInt(payload, CB_SET_RENDER_DISTANCE);
        writeVarInt(payload, viewDistance);
        payload.flip();
        return frame(payload);
    }

    // -------------------------------------------------------------------------
    // Buffer utilities — package-private so CinderConnection can share them
    // -------------------------------------------------------------------------

    /**
     * Wrap a payload with a VarInt length prefix, returning a new direct buffer.
     * The returned buffer is flipped and ready for writing to a channel.
     */
    static ByteBuffer frame(ByteBuffer payload) {
        int len = payload.remaining();
        byte[] lenBytes = encodeVarInt(len);
        ByteBuffer framed = ByteBuffer.allocateDirect(lenBytes.length + len);
        framed.put(lenBytes);
        framed.put(payload);
        framed.flip();
        return framed;
    }

    static void writeVarInt(ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    static byte[] encodeVarInt(int value) {
        byte[] tmp = new byte[5];
        int i = 0;
        while ((value & ~0x7F) != 0) {
            tmp[i++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        tmp[i++] = (byte) value;
        byte[] result = new byte[i];
        System.arraycopy(tmp, 0, result, 0, i);
        return result;
    }

    // -------------------------------------------------------------------------
    // Decode helpers
    // -------------------------------------------------------------------------

    private static int requireVarInt(ByteBuffer buf, String field) throws DecodeException {
        int value = 0, shift = 0;
        int start = buf.position();
        while (buf.hasRemaining()) {
            if (shift >= 35) {
                buf.position(start);
                throw new DecodeException("VarInt too long for field: " + field);
            }
            byte b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) return value;
        }
        buf.position(start);
        throw new DecodeException("truncated VarInt for field: " + field);
    }

    private static String requireString(ByteBuffer buf, int maxChars, String field) throws DecodeException {
        int len = requireVarInt(buf, field + " length");
        if (len < 0 || len > maxChars * 4) throw new DecodeException("string too long for field: " + field);
        if (buf.remaining() < len) throw new DecodeException("truncated string for field: " + field);
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireBytes(ByteBuffer buf, int count, String context) throws DecodeException {
        if (buf.remaining() < count) {
            throw new DecodeException(context + ": expected " + count + " bytes, got " + buf.remaining());
        }
    }

    // -------------------------------------------------------------------------
    // DecodeException
    // -------------------------------------------------------------------------

    /** Thrown when a packet's binary structure violates the expected format. */
    public static final class DecodeException extends Exception {
        public DecodeException(String message) {
            super(message, null, true, false); // suppress stack trace — not exceptional
        }
    }
}
