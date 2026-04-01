package dev.cinder.world;

import dev.cinder.chunk.ChunkPosition;

import java.util.ArrayDeque;

/**
 * Minimal queued fluid propagation system placeholder.
 *
 * Updates are queued and drained under a per-tick budget to prevent spikes.
 */
public final class FluidUpdateSystem {

    private final ArrayDeque<FluidUpdate> queue = new ArrayDeque<>();
    private int perTickBudget = 128;

    public void setPerTickBudget(int perTickBudget) {
        this.perTickBudget = Math.max(1, perTickBudget);
    }

    public int getPerTickBudget() {
        return perTickBudget;
    }

    public void queue(ChunkPosition chunk, int x, int y, int z, short fluidBlockId) {
        queue.addLast(new FluidUpdate(chunk, x, y, z, fluidBlockId));
    }

    public int tick(CinderWorld world) {
        int processed = 0;
        while (!queue.isEmpty() && processed < perTickBudget) {
            FluidUpdate update = queue.pollFirst();
            world.setBlock(update.x, update.y, update.z, update.fluidBlockId);
            processed++;
        }
        return processed;
    }

    public int pendingCount() {
        return queue.size();
    }

    private record FluidUpdate(ChunkPosition chunk, int x, int y, int z, short fluidBlockId) {
    }
}
