package dev.cinder.world;

import dev.cinder.chunk.ChunkLifecycleManager;
import dev.cinder.chunk.ChunkPosition;
import dev.cinder.chunk.CinderChunk;
import dev.cinder.entity.EntityUpdatePipeline;
import dev.cinder.server.CinderScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CinderWorldTest {

    private CinderScheduler scheduler;
    private EntityUpdatePipeline entityPipeline;
    private ChunkLifecycleManager chunkManager;
    private CinderWorld world;

    @BeforeEach
    void setUp() {
        scheduler = CinderScheduler.createDefault();
        entityPipeline = EntityUpdatePipeline.createWithWorkers(1);

        ChunkLifecycleManager.ChunkStorage storage = new ChunkLifecycleManager.ChunkStorage() {
            @Override
            public CinderChunk load(ChunkPosition pos) {
                return null;
            }

            @Override
            public void saveAsync(ChunkPosition pos, byte[] snapshot) {
            }

            @Override
            public void saveSync(ChunkPosition pos, CinderChunk chunk) {
            }
        };

        chunkManager = new ChunkLifecycleManager(scheduler, storage, 64, 1);
        world = new CinderWorld("test", chunkManager, entityPipeline, scheduler);
    }

    @AfterEach
    void tearDown() {
        chunkManager.shutdown();
        entityPipeline.shutdown();
        scheduler.shutdown();
    }

    @Test
    void loadChunk_generatesFlatTerrainOnStorageMiss() throws Exception {
        ChunkPosition pos = ChunkPosition.of(0, 0);
        AtomicReference<CinderChunk> loaded = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        world.loadChunk(pos, chunk -> {
            loaded.set(chunk);
            latch.countDown();
        });

        waitForCallback(latch);

        CinderChunk chunk = loaded.get();
        assertNotNull(chunk);

        int minX = pos.blockMinX();
        int minZ = pos.blockMinZ();
        assertEquals(FlatWorldGenerator.BEDROCK_STATE, chunk.getBlock(minX, 0, minZ));
        assertEquals(FlatWorldGenerator.DIRT_STATE, chunk.getBlock(minX, 2, minZ));
        assertEquals(FlatWorldGenerator.GRASS_STATE, chunk.getBlock(minX, FlatWorldGenerator.SURFACE_Y, minZ));
    }

    @Test
    void loadChunk_whenAlreadyLoaded_returnsSameChunkInstance() throws Exception {
        ChunkPosition pos = ChunkPosition.of(1, -2);
        CinderChunk first = loadBlocking(pos);

        AtomicReference<CinderChunk> second = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        world.loadChunk(pos, chunk -> {
            second.set(chunk);
            latch.countDown();
        });

        waitForCallback(latch);
        assertSame(first, second.get());
    }

    @Test
    void setBlock_onLoadedChunk_roundTripsViaWorldAccessors() throws Exception {
        ChunkPosition pos = ChunkPosition.of(2, 2);
        CinderChunk chunk = loadBlocking(pos);

        int x = pos.blockMinX();
        int z = pos.blockMinZ();
        short state = 321;

        world.setBlock(x, 10, z, state);
        assertEquals(state, world.getBlock(x, 10, z));
        assertSame(chunk, world.getChunkIfLoaded(pos));
    }

    private CinderChunk loadBlocking(ChunkPosition pos) throws Exception {
        AtomicReference<CinderChunk> loaded = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        world.loadChunk(pos, chunk -> {
            loaded.set(chunk);
            latch.countDown();
        });
        waitForCallback(latch);
        return loaded.get();
    }

    private void waitForCallback(CountDownLatch latch) throws Exception {
        long tick = 1;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (latch.getCount() > 0 && System.nanoTime() < deadlineNanos) {
            scheduler.drainSyncQueue(tick++);
            Thread.sleep(2);
        }

        assertTrue(latch.await(1, TimeUnit.MILLISECONDS), "Timed out waiting for chunk callback");
    }
}