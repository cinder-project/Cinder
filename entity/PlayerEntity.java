package dev.cinder.entity;

import dev.cinder.chunk.ChunkLifecycleManager;
import dev.cinder.chunk.ChunkPosition;
import dev.cinder.network.CinderConnection;
import dev.cinder.network.PacketCodec;
import dev.cinder.network.PacketCodec.InboundAction;
import dev.cinder.network.PacketCodec.PlayerMoveAction;
import dev.cinder.network.PacketCodec.KeepAliveResponseAction;
import dev.cinder.network.PacketCodec.ConfirmTeleportAction;
import dev.cinder.network.PacketCodec.PlayerCommandAction;
import dev.cinder.network.PacketCodec.UnknownAction;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Represents a connected player in the tick system.
 *
 * <p>PlayerEntity is a CRITICAL-tier {@link CinderEntity}: it is updated every tick
 * without deferral. It owns:
 * <ul>
 *   <li>The player's {@link CinderConnection} — packets are dispatched here</li>
 *   <li>View-distance chunk tracking — loads/unloads chunks as the player moves</li>
 *   <li>Keep-alive bookkeeping — pings every 15 seconds, disconnects on timeout</li>
 *   <li>Pending inbound action queue — filled by the network thread, drained each tick</li>
 * </ul>
 *
 * <p>Threading contract: all methods are called on the tick thread unless explicitly noted.
 * {@link #enqueueAction(InboundAction)} is the only method safe to call from the network thread.
 *
 * <p>Chunk loading uses {@link ChunkLifecycleManager#requestChunkWithCallback} so chunks
 * are loaded asynchronously and promoted to the cache on arrival. Holder counts are
 * incremented for every chunk in view and decremented on unload, preventing LRU eviction
 * of chunks the player is actively in.
 */
public final class PlayerEntity extends CinderEntity {

    private static final Logger LOG = Logger.getLogger("cinder.entity.player");

    /** Default view distance if not configured. */
    public static final int DEFAULT_VIEW_DISTANCE = 8;

    /** Keep-alive ping interval in ticks (15 seconds at 20 TPS). */
    private static final long KEEP_ALIVE_INTERVAL_TICKS = 300L;

    /** Ticks the client has to respond to a keep-alive before being disconnected. */
    private static final long KEEP_ALIVE_TIMEOUT_TICKS = 600L; // 30 seconds

    /** Max inbound actions processed per tick — prevents a flood from blowing the tick budget. */
    private static final int MAX_ACTIONS_PER_TICK = 32;

    private final CinderConnection connection;
    private final ChunkLifecycleManager chunkManager;
    private final int viewDistance;

    // Teleport tracking — monotonically increasing, client must confirm each one.
    private final AtomicInteger teleportIdCounter = new AtomicInteger(0);
    private volatile int pendingTeleportId = -1; // -1 = no pending teleport

    // Keep-alive state.
    private long lastKeepAliveSentTick   = 0L;
    private long lastKeepAliveId         = 0L;
    private boolean keepAliveOutstanding = false;

    // Chunks currently held by this player (loaded on their behalf).
    // Tick-thread-only — no concurrent access.
    private final Set<ChunkPosition> heldChunks = new HashSet<>();

    // Inbound action queue — written by network thread, drained by tick thread.
    // ConcurrentLinkedQueue is lock-free; drain is single-consumer.
    private final java.util.concurrent.ConcurrentLinkedQueue<InboundAction> inboundQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Sprinting / sneaking flags — updated from PlayerCommandAction.
    private boolean sprinting = false;
    private boolean sneaking  = false;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public PlayerEntity(
            CinderConnection connection,
            ChunkLifecycleManager chunkManager,
            int viewDistance) {
        super(0.0, 64.0, 0.0); // spawn position; overridden by first position sync
        this.connection   = connection;
        this.chunkManager = chunkManager;
        this.viewDistance = Math.max(2, Math.min(viewDistance, 32));
        setTier(EntityTier.CRITICAL);
    }

    public PlayerEntity(CinderConnection connection, ChunkLifecycleManager chunkManager) {
        this(connection, chunkManager, DEFAULT_VIEW_DISTANCE);
    }

    // -------------------------------------------------------------------------
    // Spawn / despawn hooks
    // -------------------------------------------------------------------------

    /**
     * Called by the tick thread when this entity is added to the world.
     * Sends Login (Play) and initial position sync, loads spawn chunks.
     */
    @Override
    protected void onSpawn() {
        // Send Login (Play) to fully transition client into play state.
        ByteBuffer loginPlay = PacketCodec.encodeLoginPlay(
                (int) getEntityId(), false, viewDistance, viewDistance);
        connection.enqueuePacket(loginPlay);

        // Send render distance.
        connection.enqueuePacket(PacketCodec.encodeSetRenderDistance(viewDistance));

        // Teleport player to spawn position.
        sendPositionSync();

        // Load initial view-distance chunks around spawn.
        updateChunkView(null);

        LOG.info("[player] Spawned: " + connection.getPlayerName()
                + " entityId=" + getEntityId()
                + " viewDistance=" + viewDistance);
    }

    @Override
    protected void onDespawn() {
        // Release all held chunks so they become LRU-eligible.
        for (ChunkPosition pos : heldChunks) {
            chunkManager.removeHolder(pos);
        }
        heldChunks.clear();
        LOG.info("[player] Despawned: " + connection.getPlayerName());
    }

    @Override
    protected void onChunkChanged(ChunkPosition from, ChunkPosition to) {
        // Player crossed a chunk boundary — update view.
        updateChunkView(from);
        // Tell client where its chunk center is now.
        connection.enqueuePacket(PacketCodec.encodeSetCenterChunk(to.x(), to.z()));
    }

    // -------------------------------------------------------------------------
    // Tick (CRITICAL tier — runs every tick, no deferral)
    // -------------------------------------------------------------------------

    @Override
    protected void onTick(long tickNumber) {
        if (connection.isClosed()) {
            kill();
            return;
        }

        drainInboundQueue(tickNumber);
        tickKeepAlive(tickNumber);
    }

    // -------------------------------------------------------------------------
    // Inbound action dispatch
    // -------------------------------------------------------------------------

    /**
     * Enqueue a decoded inbound action from the network thread.
     * This is the only method safe to call off the tick thread.
     */
    public void enqueueAction(InboundAction action) {
        inboundQueue.offer(action);
    }

    private void drainInboundQueue(long tickNumber) {
        int processed = 0;
        InboundAction action;
        while (processed < MAX_ACTIONS_PER_TICK && (action = inboundQueue.poll()) != null) {
            dispatchAction(action, tickNumber);
            processed++;
        }
    }

    private void dispatchAction(InboundAction action, long tickNumber) {
        switch (action) {
            case ConfirmTeleportAction a   -> handleConfirmTeleport(a);
            case PlayerMoveAction a        -> handleMove(a);
            case KeepAliveResponseAction a -> handleKeepAliveResponse(a, tickNumber);
            case PlayerCommandAction a     -> handlePlayerCommand(a);
            case UnknownAction a           -> { /* silently ignore */ }
            default                        -> { /* future action types */ }
        }
    }

    private void handleConfirmTeleport(ConfirmTeleportAction action) {
        if (action.teleportId() == pendingTeleportId) {
            pendingTeleportId = -1; // teleport acknowledged
        }
    }

    private void handleMove(PlayerMoveAction action) {
        // Discard movement while a teleport is unconfirmed — client position is stale.
        if (pendingTeleportId != -1) return;

        double newX = action.hasPosition() ? action.x() : getX();
        double newY = action.hasPosition() ? action.y() : getY();
        double newZ = action.hasPosition() ? action.z() : getZ();

        // Basic sanity bound — reject absurd position deltas (could be exploit or corruption).
        if (action.hasPosition()) {
            double dx = newX - getX();
            double dy = newY - getY();
            double dz = newZ - getZ();
            if (dx * dx + dy * dy + dz * dz > 100.0 * 100.0) {
                // Delta > 100 blocks in one tick — resync.
                sendPositionSync();
                return;
            }
        }

        setPosition(newX, newY, newZ);

        if (action.hasRotation()) {
            setRotation(action.yaw(), action.pitch());
        }
    }

    private void handleKeepAliveResponse(KeepAliveResponseAction action, long tickNumber) {
        if (keepAliveOutstanding && action.id() == lastKeepAliveId) {
            keepAliveOutstanding = false;
        }
    }

    private void handlePlayerCommand(PlayerCommandAction action) {
        switch (action.command()) {
            case START_SPRINTING -> sprinting = true;
            case STOP_SPRINTING  -> sprinting = false;
            case START_SNEAKING  -> sneaking  = true;
            case STOP_SNEAKING   -> sneaking  = false;
            default              -> { /* other commands unhandled in Phase 2 */ }
        }
    }

    // -------------------------------------------------------------------------
    // Keep-alive
    // -------------------------------------------------------------------------

    private void tickKeepAlive(long tickNumber) {
        if (keepAliveOutstanding) {
            long ticksSincePing = tickNumber - lastKeepAliveSentTick;
            if (ticksSincePing > KEEP_ALIVE_TIMEOUT_TICKS) {
                connection.close("keep-alive timeout");
                kill();
            }
            return;
        }

        if (tickNumber - lastKeepAliveSentTick >= KEEP_ALIVE_INTERVAL_TICKS) {
            lastKeepAliveId       = System.nanoTime();
            lastKeepAliveSentTick = tickNumber;
            keepAliveOutstanding  = true;
            connection.enqueuePacket(PacketCodec.encodeKeepAlive(lastKeepAliveId));
        }
    }

    // -------------------------------------------------------------------------
    // View-distance chunk management
    // -------------------------------------------------------------------------

    /**
     * Compute the set of chunks that should be loaded for the player's current position,
     * load any that are new, and release any that have left the view.
     *
     * @param previousChunk the chunk the player was in before moving, or null on first call
     */
    private void updateChunkView(ChunkPosition previousChunk) {
        ChunkPosition center = getChunkPosition();
        Set<ChunkPosition> desired = chunksInView(center, viewDistance);

        // Load chunks that entered view.
        for (ChunkPosition pos : desired) {
            if (!heldChunks.contains(pos)) {
                chunkManager.addHolder(pos);
                chunkManager.requestChunkWithCallback(pos, chunk -> {
                    // Callback runs on cinder-chunk-io, promoted to tick thread by
                    // ChunkLifecycleManager. Nothing to do here for Phase 2 —
                    // chunk data packets are Phase 3 (world engine).
                });
                heldChunks.add(pos);
            }
        }

        // Release chunks that left view.
        heldChunks.removeIf(pos -> {
            if (!desired.contains(pos)) {
                chunkManager.removeHolder(pos);
                return true;
            }
            return false;
        });
    }

    /**
     * Return all chunk positions within {@code radius} chunks of {@code center}.
     * Produces a (2*radius+1)² square — matches vanilla client behaviour.
     */
    static Set<ChunkPosition> chunksInView(ChunkPosition center, int radius) {
        Set<ChunkPosition> result = new HashSet<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                result.add(center.offset(dx, dz));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Position sync
    // -------------------------------------------------------------------------

    private void sendPositionSync() {
        int id = teleportIdCounter.incrementAndGet();
        pendingTeleportId = id;
        connection.enqueuePacket(PacketCodec.encodePlayerPositionSync(
                getX(), getY(), getZ(), getYaw(), getPitch(), id));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public CinderConnection getConnection() { return connection; }
    public boolean isSprinting()            { return sprinting;  }
    public boolean isSneaking()             { return sneaking;   }
    public int getViewDistance()            { return viewDistance; }
    public int getHeldChunkCount()          { return heldChunks.size(); }

    /** True if the client has an unacknowledged position sync outstanding. */
    public boolean hasPendingTeleport()     { return pendingTeleportId != -1; }

    @Override
    protected EntityTier defaultTier() {
        return EntityTier.CRITICAL;
    }
}
