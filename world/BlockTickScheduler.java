package dev.cinder.world;

import dev.cinder.chunk.ChunkPosition;

import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Tick-delayed block update scheduler with per-tick processing budget.
 */
public final class BlockTickScheduler {

    private final PriorityQueue<ScheduledBlockTick> queue =
        new PriorityQueue<>((a, b) -> Long.compare(a.executeAtTick, b.executeAtTick));

    private int perTickBudget = 256;

    public void setPerTickBudget(int perTickBudget) {
        this.perTickBudget = Math.max(1, perTickBudget);
    }

    public int getPerTickBudget() {
        return perTickBudget;
    }

    public void schedule(
            ChunkPosition chunk,
            int x,
            int y,
            int z,
            long executeAtTick,
            Consumer<ScheduledBlockTick> callback
    ) {
        queue.add(new ScheduledBlockTick(chunk, x, y, z, executeAtTick, callback));
    }

    public int tick(long currentTick) {
        int processed = 0;
        while (!queue.isEmpty() && processed < perTickBudget) {
            ScheduledBlockTick next = queue.peek();
            if (next.executeAtTick > currentTick) {
                break;
            }
            queue.poll();
            if (next.callback != null) {
                next.callback.accept(next);
            }
            processed++;
        }
        return processed;
    }

    public int pendingCount() {
        return queue.size();
    }

    public static final class ScheduledBlockTick {
        public final ChunkPosition chunk;
        public final int x;
        public final int y;
        public final int z;
        public final long executeAtTick;
        public final Consumer<ScheduledBlockTick> callback;

        private ScheduledBlockTick(
                ChunkPosition chunk,
                int x,
                int y,
                int z,
                long executeAtTick,
                Consumer<ScheduledBlockTick> callback
        ) {
            this.chunk = chunk;
            this.x = x;
            this.y = y;
            this.z = z;
            this.executeAtTick = executeAtTick;
            this.callback = callback;
        }
    }
}
