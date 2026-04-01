package dev.cinder.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal benchmark runner harness for warmup/steady/cooldown execution.
 *
 * This runner is intentionally external-load agnostic: it executes optional
 * shell hooks to start/stop a load generator and writes a structured result.
 */
public final class CinderBenchRunner {

    private CinderBenchRunner() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);

        Instant started = Instant.now();

        if (!config.startHook.isBlank()) {
            runHook(config.startHook, "start-hook");
        }

        sleepSeconds(config.warmupSec);
        Instant steadyStart = Instant.now();
        sleepSeconds(config.steadySec);
        Instant steadyEnd = Instant.now();

        if (!config.stopHook.isBlank()) {
            runHook(config.stopHook, "stop-hook");
        }

        sleepSeconds(config.cooldownSec);
        Instant ended = Instant.now();

        String json = toJson(config, started, steadyStart, steadyEnd, ended);
        Files.createDirectories(config.output.getParent());
        Files.writeString(config.output, json, StandardCharsets.UTF_8);

        System.out.println("[CinderBenchRunner] Result written: " + config.output);
    }

    private static void sleepSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while sleeping", e);
        }
    }

    private static void runHook(String hook, String label) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-lc", hook)
            .inheritIO()
            .start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Hook failed: " + label + " exit=" + exit);
        }
    }

    private static String toJson(
            Config config,
            Instant started,
            Instant steadyStart,
            Instant steadyEnd,
            Instant ended
    ) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", config.scenario);
        root.put("startedAtUtc", fmt.format(started));
        root.put("steadyStartUtc", fmt.format(steadyStart));
        root.put("steadyEndUtc", fmt.format(steadyEnd));
        root.put("endedAtUtc", fmt.format(ended));
        root.put("warmupSec", config.warmupSec);
        root.put("steadySec", config.steadySec);
        root.put("cooldownSec", config.cooldownSec);
        root.put("status", "completed");

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            sb.append("  \"").append(escape(entry.getKey())).append("\": ");
            Object value = entry.getValue();
            if (value instanceof Number) {
                sb.append(value);
            } else {
                sb.append("\"").append(escape(String.valueOf(value))).append("\"");
            }
            if (i < root.size() - 1) {
                sb.append(',');
            }
            sb.append("\n");
            i++;
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Config {
        final String scenario;
        final int warmupSec;
        final int steadySec;
        final int cooldownSec;
        final String startHook;
        final String stopHook;
        final Path output;

        private Config(
                String scenario,
                int warmupSec,
                int steadySec,
                int cooldownSec,
                String startHook,
                String stopHook,
                Path output
        ) {
            this.scenario = scenario;
            this.warmupSec = warmupSec;
            this.steadySec = steadySec;
            this.cooldownSec = cooldownSec;
            this.startHook = startHook;
            this.stopHook = stopHook;
            this.output = output;
        }

        static Config parse(String[] args) {
            String scenario = "custom";
            int warmupSec = 30;
            int steadySec = 120;
            int cooldownSec = 15;
            String startHook = "";
            String stopHook = "";
            Path output = Path.of("cinder-bench", "results", "runner-"
                + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now()) + ".json");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--scenario" -> scenario = requireArg(args, ++i, "--scenario");
                    case "--warmup-sec" -> warmupSec = parseInt(requireArg(args, ++i, "--warmup-sec"), "--warmup-sec");
                    case "--steady-sec" -> steadySec = parseInt(requireArg(args, ++i, "--steady-sec"), "--steady-sec");
                    case "--cooldown-sec" -> cooldownSec = parseInt(requireArg(args, ++i, "--cooldown-sec"), "--cooldown-sec");
                    case "--start-hook" -> startHook = requireArg(args, ++i, "--start-hook");
                    case "--stop-hook" -> stopHook = requireArg(args, ++i, "--stop-hook");
                    case "--output" -> output = Path.of(requireArg(args, ++i, "--output"));
                    case "-h", "--help" -> printAndExit();
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (steadySec <= 0) {
                throw new IllegalArgumentException("--steady-sec must be > 0");
            }
            if (warmupSec < 0 || cooldownSec < 0) {
                throw new IllegalArgumentException("warmup/cooldown must be >= 0");
            }

            return new Config(scenario, warmupSec, steadySec, cooldownSec, startHook, stopHook, output);
        }

        private static String requireArg(String[] args, int idx, String flag) {
            if (idx >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[idx];
        }

        private static int parseInt(String value, String flag) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid integer for " + flag + ": " + value);
            }
        }

        private static void printAndExit() {
            String text = """
                Usage: CinderBenchRunner [options]

                Options:
                  --scenario <name>
                  --warmup-sec <n>
                  --steady-sec <n>
                  --cooldown-sec <n>
                  --start-hook <shell-command>
                  --stop-hook <shell-command>
                  --output <path>
                """;
            System.out.println(text);
            System.exit(0);
        }
    }
}
