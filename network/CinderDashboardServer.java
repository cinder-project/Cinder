package dev.cinder.network;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.cinder.profiling.TickProfiler.RollingStats;
import dev.cinder.server.CinderServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded HTTP dashboard server for Cinder runtime nodes.
 *
 * <p>This class serves two responsibilities on port 8080 by default:
 * <p>1. Static single-page dashboard assets under /, /style.css, and /cinder.js.
 * <p>2. Live JSON metrics at /api/metrics for front-end polling.
 *
 * <p>The implementation is intentionally framework-free and uses only JDK classes
 * to keep runtime overhead low on Raspberry Pi 4/400 hardware.
 */
public final class CinderDashboardServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("cinder.dashboard");

    private static final int DEFAULT_PORT = 8080;
    private static final int STATIC_OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int INTERNAL_ERROR = 500;

    private final HttpServer httpServer;
    private final Supplier<CinderServer.ServerStats> statsSupplier;
    private final IntSupplier playerCountSupplier;
    private final Path dashboardDir;
    private final Path dataDir;
    private final Instant startedAt;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a dashboard server instance bound to a specific address.
     *
     * @param bindAddress Address and port to bind, for example 0.0.0.0:8080.
     * @param statsSupplier Supplier of the latest server stats snapshot.
     * @param playerCountSupplier Supplier for active player/connection count.
     * @param dashboardDir Directory containing static dashboard assets.
     * @param dataDir Base data directory used for log and disk metrics.
     * @throws IOException If the HTTP server socket cannot be opened.
     */
    public CinderDashboardServer(
            InetSocketAddress bindAddress,
            Supplier<CinderServer.ServerStats> statsSupplier,
            IntSupplier playerCountSupplier,
            Path dashboardDir,
            Path dataDir
    ) throws IOException {
        this.statsSupplier = Objects.requireNonNull(statsSupplier, "statsSupplier");
        this.playerCountSupplier = Objects.requireNonNull(playerCountSupplier, "playerCountSupplier");
        this.dashboardDir = Objects.requireNonNull(dashboardDir, "dashboardDir");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.startedAt = Instant.now();

        this.httpServer = HttpServer.create(bindAddress, 64);
        this.httpServer.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cinder-dashboard-http");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }));

        this.httpServer.createContext("/api/metrics", new MetricsHandler());
        this.httpServer.createContext("/", new StaticHandler());
    }

    /**
     * Creates a dashboard server with Cinder defaults.
     *
     * @param statsSupplier Supplier of the latest server stats snapshot.
     * @param playerCountSupplier Supplier for active player/connection count.
     * @return A configured dashboard server instance.
     * @throws IOException If the HTTP server socket cannot be opened.
     */
    public static CinderDashboardServer createDefault(
            Supplier<CinderServer.ServerStats> statsSupplier,
            IntSupplier playerCountSupplier
    ) throws IOException {
        return new CinderDashboardServer(
            new InetSocketAddress("0.0.0.0", DEFAULT_PORT),
            statsSupplier,
            playerCountSupplier,
            Path.of("/opt/cinder/dashboard"),
            Path.of("/data")
        );
    }

    /**
     * Starts the HTTP server if it is not already running.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        httpServer.start();
        LOG.info("[dashboard] Listening on port " + httpServer.getAddress().getPort());
    }

    /**
     * Stops the HTTP server and releases its listening socket.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        httpServer.stop(0);
        LOG.info("[dashboard] Stopped");
    }

    /**
     * Returns whether the dashboard server is currently running.
     *
     * @return true when started and not yet stopped.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Stops the server. Alias for {@link #stop()}.
     */
    @Override
    public void close() {
        stop();
    }

    private final class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    writeResponse(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                    return;
                }

                String payload = buildMetricsJson();
                writeResponse(exchange, STATIC_OK, "application/json; charset=utf-8", payload);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[dashboard] Metrics handler failure", e);
                writeResponse(exchange, INTERNAL_ERROR, "application/json", "{\"error\":\"internal_error\"}");
            }
        }
    }

    private final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeResponse(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
                return;
            }

            String requestPath = exchange.getRequestURI().getPath();
            String resolvedAsset;
            if (requestPath == null || "/".equals(requestPath) || requestPath.isBlank()) {
                resolvedAsset = "index.html";
            } else if ("/style.css".equals(requestPath)) {
                resolvedAsset = "style.css";
            } else if ("/cinder.js".equals(requestPath)) {
                resolvedAsset = "cinder.js";
            } else {
                writeResponse(exchange, NOT_FOUND, "text/plain; charset=utf-8", "Not Found");
                return;
            }

            Path assetPath = dashboardDir.resolve(resolvedAsset).normalize();
            if (!assetPath.startsWith(dashboardDir.normalize()) || !Files.isRegularFile(assetPath)) {
                writeResponse(exchange, NOT_FOUND, "text/plain; charset=utf-8", "Not Found");
                return;
            }

            String mime = mimeTypeFor(resolvedAsset);
            byte[] body = Files.readAllBytes(assetPath);

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", mime);
            headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
            exchange.sendResponseHeaders(STATIC_OK, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static String mimeTypeFor(String assetName) {
        if (assetName.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (assetName.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private String buildMetricsJson() {
        CinderServer.ServerStats stats = statsSupplier.get();
        RollingStats rolling = (stats != null) ? stats.rolling() : RollingStats.EMPTY;

        int playerCount = Math.max(0, playerCountSupplier.getAsInt());
        double cpuTempC = readCpuTempC();
        long[] mem = readJvmMemory();
        long[] disk = readDiskUsage();
        long uptimeSeconds = Duration.between(startedAt, Instant.now()).getSeconds();
        List<String> logs = readLastLogLines(50);

        String state = (stats != null && stats.serverState() != null)
            ? stats.serverState().name().toLowerCase(Locale.ROOT)
            : "unknown";

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        sb.append("\"state\":\"").append(jsonEscape(state)).append("\",");
        sb.append("\"tps\":").append(formatDouble(rolling.tps)).append(',');
        sb.append("\"mspt\":{");
        sb.append("\"mean\":").append(formatDouble(rolling.meanMspt)).append(',');
        sb.append("\"p50\":").append(formatDouble(rolling.p50Mspt)).append(',');
        sb.append("\"p95\":").append(formatDouble(rolling.p95Mspt)).append(',');
        sb.append("\"p99\":").append(formatDouble(rolling.p99Mspt)).append("},");
        sb.append("\"players\":").append(playerCount).append(',');
        sb.append("\"cpuTempC\":").append(formatDouble(cpuTempC)).append(',');
        sb.append("\"memory\":{");
        sb.append("\"usedBytes\":").append(mem[0]).append(',');
        sb.append("\"maxBytes\":").append(mem[1]).append("},");
        sb.append("\"disk\":{");
        sb.append("\"usedBytes\":").append(disk[0]).append(',');
        sb.append("\"totalBytes\":").append(disk[1]).append("},");
        sb.append("\"uptimeSeconds\":").append(uptimeSeconds).append(',');
        sb.append("\"lastLogLines\":[");

        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(logs.get(i))).append('"');
        }

        sb.append("]}");
        return sb.toString();
    }

    private long[] readJvmMemory() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return new long[] { Math.max(0L, used), Math.max(0L, max) };
    }

    private long[] readDiskUsage() {
        Path target = dataDir;
        if (!Files.isDirectory(target)) {
            target = Path.of("/");
        }

        try {
            FileStore store = Files.getFileStore(target);
            long total = store.getTotalSpace();
            long unallocated = store.getUnallocatedSpace();
            long used = Math.max(0L, total - unallocated);
            return new long[] { used, total };
        } catch (IOException e) {
            return new long[] { 0L, 0L };
        }
    }

    private double readCpuTempC() {
        Path tempPath = Path.of("/sys/class/thermal/thermal_zone0/temp");
        if (!Files.isRegularFile(tempPath)) {
            return 0.0;
        }

        try {
            String raw = Files.readString(tempPath, StandardCharsets.UTF_8).trim();
            long milliC = Long.parseLong(raw);
            return milliC / 1000.0;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private List<String> readLastLogLines(int maxLines) {
        Path logsDir = dataDir.resolve("logs");
        if (!Files.isDirectory(logsDir)) {
            return List.of();
        }

        Path newestLog;
        try (var stream = Files.list(logsDir)) {
            newestLog = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".log"))
                .max(Comparator.comparingLong(this::lastModifiedOrZero))
                .orElse(null);
        } catch (IOException e) {
            return List.of();
        }

        if (newestLog == null) {
            return List.of();
        }

        Deque<String> tail = new ArrayDeque<>(maxLines);
        try (BufferedReader reader = Files.newBufferedReader(newestLog, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() >= maxLines) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException e) {
            return List.of();
        }

        return new ArrayList<>(tail);
    }

    private long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String formatDouble(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static void writeResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
