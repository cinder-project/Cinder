package dev.cinder.network;

import dev.cinder.server.CinderScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * TCP acceptor and per-connection lifecycle manager for the Cinder network layer.
 *
 * <p>Runs a single NIO selector on a dedicated thread. Accepted connections are
 * handed to {@link CinderConnection} instances which own their own read/write state.
 * All world mutations initiated by packet handling go through {@link CinderScheduler#submitSync}.
 *
 * <p>Threading model:
 * <pre>
 *   cinder-net-accept   — selector loop, accept + OP_READ dispatch
 *   cinder-net-write-*  — outbound flush workers (one per connection, pooled)
 *   cinder-tick         — calls {@link #flushAll()} during POST phase
 * </pre>
 *
 * <p>PROXY protocol v2 is supported via {@link #setProxyProtocolEnabled(boolean)}.
 * Connection rate limiting is enforced per source IP over a sliding window.
 */
public final class CinderNetworkManager {

    private static final Logger LOG = Logger.getLogger("cinder.network");

    // Rate limit: max new connections per source IP within the window.
    private static final int RATE_LIMIT_MAX_CONNECTIONS = 5;
    private static final long RATE_LIMIT_WINDOW_NS = TimeUnit.SECONDS.toNanos(10);

    // Hard cap on total simultaneous connections.
    private static final int MAX_CONNECTIONS = 100;

    // Selector timeout — short enough for responsive shutdown, long enough to not spin.
    private static final long SELECT_TIMEOUT_MS = 50;

    private final CinderScheduler scheduler;
    private final String bindHost;
    private final int bindPort;

    private volatile boolean proxyProtocolEnabled = false;

    private ServerSocketChannel serverChannel;
    private Selector selector;

    // Active connections keyed by their SocketChannel for O(1) selector-event dispatch.
    private final Map<SocketChannel, CinderConnection> connections = new ConcurrentHashMap<>();

    // Rate limiting: source IP → list of connection timestamps (nanoseconds).
    private final Map<String, LongArrayDeque> rateLimitTable = new ConcurrentHashMap<>();

    // Write flush executor — shared pool, not per-connection threads.
    private final ExecutorService writeExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger totalAccepted = new AtomicInteger(0);
    private final AtomicInteger totalRejected = new AtomicInteger(0);

    private Thread acceptThread;

    public CinderNetworkManager(CinderScheduler scheduler, String bindHost, int bindPort) {
        this.scheduler = scheduler;
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        // Pi 4 has 4 cores. Two write workers is enough for buffered outbound flushing
        // without starving the tick thread or the accept loop.
        this.writeExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cinder-net-write");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Bind and start accepting connections. Non-blocking after this returns.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("NetworkManager already started");
        }

        selector = Selector.open();

        serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(bindHost, bindPort), /* backlog */ 64);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        LOG.info("[network] Listening on " + bindHost + ":" + bindPort
                + (proxyProtocolEnabled ? " (PROXY protocol v2 enabled)" : ""));

        acceptThread = new Thread(this::selectLoop, "cinder-net-accept");
        acceptThread.setDaemon(true);
        acceptThread.setPriority(Thread.NORM_PRIORITY);
        acceptThread.start();
    }

    /**
     * Graceful shutdown. Closes all connections, then the server channel.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        selector.wakeup(); // unblock selectLoop

        for (CinderConnection conn : connections.values()) {
            conn.close("server shutdown");
        }
        connections.clear();

        try { serverChannel.close(); } catch (IOException ignored) {}
        try { selector.close(); } catch (IOException ignored) {}

        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        LOG.info("[network] Stopped. accepted=" + totalAccepted + " rejected=" + totalRejected);
    }

    // -------------------------------------------------------------------------
    // Selector loop (cinder-net-accept thread)
    // -------------------------------------------------------------------------

    private void selectLoop() {
        while (running.get()) {
            try {
                int ready = selector.select(SELECT_TIMEOUT_MS);
                if (ready == 0) {
                    pruneRateLimitTable();
                    continue;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        acceptConnection();
                    } else if (key.isReadable()) {
                        CinderConnection conn = (CinderConnection) key.attachment();
                        if (conn != null) {
                            handleRead(conn, key);
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    LOG.warning("[network] Selector error: " + e.getMessage());
                }
            }
        }
    }

    private void acceptConnection() {
        SocketChannel channel;
        try {
            channel = serverChannel.accept();
            if (channel == null) return; // spurious wakeup
        } catch (IOException e) {
            LOG.warning("[network] Accept failed: " + e.getMessage());
            return;
        }

        InetSocketAddress remoteAddr;
        try {
            remoteAddr = (InetSocketAddress) channel.getRemoteAddress();
        } catch (IOException e) {
            silentClose(channel);
            return;
        }

        String sourceIp = remoteAddr.getAddress().getHostAddress();

        // Hard cap.
        if (connections.size() >= MAX_CONNECTIONS) {
            LOG.warning("[network] Connection refused (cap=" + MAX_CONNECTIONS + "): " + sourceIp);
            totalRejected.incrementAndGet();
            silentClose(channel);
            return;
        }

        // Rate limit.
        if (isRateLimited(sourceIp)) {
            LOG.warning("[network] Connection rate-limited: " + sourceIp);
            totalRejected.incrementAndGet();
            silentClose(channel);
            return;
        }

        try {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

            CinderConnection conn = new CinderConnection(
                    channel, remoteAddr, scheduler, writeExecutor, proxyProtocolEnabled,
                    this::onConnectionClosed);

            SelectionKey key = channel.register(selector, SelectionKey.OP_READ, conn);
            conn.setSelectionKey(key);
            connections.put(channel, conn);

            totalAccepted.incrementAndGet();
            LOG.fine("[network] Accepted: " + sourceIp + " total=" + connections.size());
        } catch (IOException e) {
            LOG.warning("[network] Failed to register channel: " + e.getMessage());
            silentClose(channel);
        }
    }

    private void handleRead(CinderConnection conn, SelectionKey key) {
        try {
            conn.readInbound();
        } catch (Exception e) {
            LOG.fine("[network] Read error [" + conn.remoteAddress() + "]: " + e.getMessage());
            conn.close("read error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // POST-phase flush (called from tick thread during POST)
    // -------------------------------------------------------------------------

    /**
     * Enqueue outbound flushes for all active connections.
     * Called by the tick loop during the POST phase after all world mutations
     * for this tick are finalised.
     *
     * <p>Actual IO happens on {@code cinder-net-write} threads — this method
     * returns immediately.
     */
    public void flushAll() {
        for (CinderConnection conn : connections.values()) {
            if (conn.hasPendingWrites()) {
                writeExecutor.submit(conn::flushOutbound);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle callback
    // -------------------------------------------------------------------------

    private void onConnectionClosed(CinderConnection conn) {
        connections.remove(conn.channel());
        // Cancel the selection key to prevent further selector events.
        SelectionKey key = conn.getSelectionKey();
        if (key != null) key.cancel();
        LOG.fine("[network] Disconnected: " + conn.remoteAddress()
                + " remaining=" + connections.size());
    }

    // -------------------------------------------------------------------------
    // Rate limiting
    // -------------------------------------------------------------------------

    private boolean isRateLimited(String sourceIp) {
        long now = System.nanoTime();
        LongArrayDeque timestamps = rateLimitTable.computeIfAbsent(sourceIp, k -> new LongArrayDeque());
        synchronized (timestamps) {
            // Evict entries outside the window.
            long cutoff = now - RATE_LIMIT_WINDOW_NS;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= RATE_LIMIT_MAX_CONNECTIONS) {
                return true;
            }
            timestamps.addLast(now);
            return false;
        }
    }

    /**
     * Remove stale IP entries that have no recent connections.
     * Called periodically from the selector loop to prevent unbounded map growth.
     */
    private void pruneRateLimitTable() {
        long cutoff = System.nanoTime() - RATE_LIMIT_WINDOW_NS;
        rateLimitTable.entrySet().removeIf(e -> {
            LongArrayDeque d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst() < cutoff) d.pollFirst();
                return d.isEmpty();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public void setProxyProtocolEnabled(boolean enabled) {
        this.proxyProtocolEnabled = enabled;
    }

    public boolean isProxyProtocolEnabled() {
        return proxyProtocolEnabled;
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    public int getConnectionCount() { return connections.size(); }
    public int getTotalAccepted() { return totalAccepted.get(); }
    public int getTotalRejected() { return totalRejected.get(); }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static void silentClose(Channel ch) {
        try { ch.close(); } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Minimal deque backed by a long[] ring buffer — avoids boxing for timestamps.
    // -------------------------------------------------------------------------

    static final class LongArrayDeque {
        private long[] buf = new long[8];
        private int head = 0, tail = 0, size = 0;

        boolean isEmpty() { return size == 0; }
        int size() { return size; }

        void addLast(long v) {
            if (size == buf.length) grow();
            buf[tail] = v;
            tail = (tail + 1) % buf.length;
            size++;
        }

        long peekFirst() {
            if (size == 0) throw new NoSuchElementException();
            return buf[head];
        }

        long pollFirst() {
            if (size == 0) throw new NoSuchElementException();
            long v = buf[head];
            head = (head + 1) % buf.length;
            size--;
            return v;
        }

        private void grow() {
            long[] next = new long[buf.length * 2];
            if (tail > head) {
                System.arraycopy(buf, head, next, 0, size);
            } else {
                int firstPart = buf.length - head;
                System.arraycopy(buf, head, next, 0, firstPart);
                System.arraycopy(buf, 0, next, firstPart, tail);
            }
            buf = next;
            head = 0;
            tail = size;
        }

        private static class NoSuchElementException extends RuntimeException {
            NoSuchElementException() { super(null, null, true, false); }
        }
    }
}
