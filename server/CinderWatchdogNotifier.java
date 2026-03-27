package dev.cinder.server;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CinderWatchdogNotifier — systemd sd_notify integration for Cinder Runtime.
 *
 * Responsibilities:
 *   1. READY=1       — sent once when the tick loop enters steady state.
 *                      Signals systemd that the service is up and accepting work.
 *                      Until this is sent, systemd holds the start transaction open.
 *
 *   2. WATCHDOG=1    — sent every PING_INTERVAL_SECONDS on a background thread.
 *                      systemd's WatchdogSec=90s requires at least one ping per 90s.
 *                      We ping every 30s to give two missed pings before a kill.
 *
 *   3. STOPPING=1    — sent during CinderServer shutdown so systemd can distinguish
 *                      a clean stop from a silent crash.
 *
 *   4. STATUS=...    — optional status string written periodically for
 *                      `systemctl status cinder` display. Shows TPS and MSPT.
 *
 * Transport:
 *   sd_notify communicates via a Unix domain datagram socket whose path is
 *   provided by systemd in the NOTIFY_SOCKET environment variable. If the
 *   variable is absent (dev/test environment), all notify calls are no-ops.
 *
 * Thread safety:
 *   notify() is synchronized. The watchdog scheduler is a single-thread
 *   ScheduledExecutorService. All public methods are safe to call from any thread.
 */
public final class CinderWatchdogNotifier {

    private static final Logger LOG = Logger.getLogger("cinder.watchdog");

    public static final int PING_INTERVAL_SECONDS = 30;

    private final String           notifySocket;
    private final AtomicBoolean    active = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cinder-watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private ScheduledFuture<?> pingTask;

    public CinderWatchdogNotifier() {
        this.notifySocket = System.getenv("NOTIFY_SOCKET");
        if (notifySocket == null) {
            LOG.info("[Watchdog] NOTIFY_SOCKET not set — sd_notify disabled (dev/test mode).");
        } else {
            LOG.info("[Watchdog] NOTIFY_SOCKET=" + notifySocket);
        }
    }

    /**
     * Sends READY=1 and starts the periodic WATCHDOG=1 ping.
     * Call once the tick loop has completed its first tick and is in steady state.
     */
    public void notifyReady() {
        if (!active.compareAndSet(false, true)) return;

        notify("READY=1");
        LOG.info("[Watchdog] Sent READY=1.");

        pingTask = scheduler.scheduleAtFixedRate(
            this::ping,
            PING_INTERVAL_SECONDS,
            PING_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        LOG.info("[Watchdog] Watchdog ping started — interval=" + PING_INTERVAL_SECONDS + "s.");
    }

    /**
     * Sends STOPPING=1 and shuts down the watchdog ping.
     * Call early in CinderServer.shutdown() before the tick loop stops.
     */
    public void notifyStopping() {
        notify("STOPPING=1");
        LOG.info("[Watchdog] Sent STOPPING=1.");
        shutdown();
    }

    /**
     * Sends a STATUS= string for display in `systemctl status cinder`.
     * Non-blocking best-effort; failures are logged at FINE level only.
     *
     * @param status  Short human-readable status line (e.g. "TPS=20.0 MSPT=4.2ms players=3")
     */
    public void notifyStatus(String status) {
        if (status == null || status.isBlank()) return;
        notify("STATUS=" + status.replace('\n', ' '));
    }

    /**
     * Sends a single WATCHDOG=1 ping. Called periodically by the scheduler
     * and may also be called manually if a subsystem wants to confirm liveness
     * during a long-running operation (e.g., world save on shutdown).
     */
    public void ping() {
        notify("WATCHDOG=1");
        LOG.fine("[Watchdog] Ping sent.");
    }

    private void shutdown() {
        if (pingTask != null) {
            pingTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void notify(String message) {
        if (notifySocket == null) return;

        String path = notifySocket.startsWith("@")
            ? "\0" + notifySocket.substring(1)
            : notifySocket;

        try {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(path);
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(addr);
                ByteBuffer buf = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "[Watchdog] Failed to send '" + message + "': " + e.getMessage());
        }
    }
}
