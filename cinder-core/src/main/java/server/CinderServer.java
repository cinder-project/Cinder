package server;

import profiling.MetricsRegistry;
import profiling.ServerProfiler;
import world.WorldTickLoop;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CinderServer {

    public static final String VERSION = "0.1.0-alpha";
    public static final String BRAND = "Cinder";

    private static final Logger LOG = Logger.getLogger(CinderServer.class.getName());

    public enum State { INIT, STARTING, RUNNING, STOPPING, STOPPED }

    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    private final ServerConfig config;
    private final MetricsRegistry metrics;
    private final ServerProfiler profiler;
    private WorldTickLoop tickLoop;

    private CinderServer(ServerConfig config) {
        this.config = config;
        this.metrics = new MetricsRegistry();
        this.profiler = new ServerProfiler(metrics);
    }

    public static CinderServer create(ServerConfig config) {
        return new CinderServer(config);
    }

    public void start() {
        if (!state.compareAndSet(State.INIT, State.STARTING)) {
            throw new IllegalStateException("Server is not in INIT state; current=" + state.get());
        }

        LOG.info(String.format("[%s] %s v%s starting — profile=%s", BRAND, BRAND, VERSION, config.profile()));

        installShutdownHook();
        profiler.begin("server.startup");

        try {
            tickLoop = new WorldTickLoop(config, metrics);
            tickLoop.initialize();

            state.set(State.RUNNING);
            profiler.end("server.startup");

            LOG.info(String.format("[%s] Running. Target TPS=%d, MSPT budget=%.1fms",
                    BRAND, config.targetTps(), config.msptBudget()));

            tickLoop.run();

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[" + BRAND + "] Fatal error during startup or runtime", e);
            state.set(State.STOPPED);
            System.exit(1);
        }
    }

    public void requestShutdown(String reason) {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        LOG.info(String.format("[%s] Shutdown requested: %s", BRAND, reason));
        state.set(State.STOPPING);
        if (tickLoop != null) {
            tickLoop.stop();
        }
    }

    public State state() {
        return state.get();
    }

    public MetricsRegistry metrics() {
        return metrics;
    }

    public ServerConfig config() {
        return config;
    }

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            requestShutdown("JVM shutdown signal");
            if (tickLoop != null) {
                tickLoop.awaitTermination(5_000L);
            }
            state.set(State.STOPPED);
            LOG.info("[" + BRAND + "] Shutdown complete.");
        }, "cinder-shutdown"));
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (var h : root.getHandlers()) {
            h.setLevel(Level.INFO);
        }
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        root.addHandler(ch);
    }

    public static void main(String[] args) {
        configureLogging();
        ServerConfig config = ServerConfig.fromArgs(args);
        CinderServer server = CinderServer.create(config);
        server.start();
    }
}
