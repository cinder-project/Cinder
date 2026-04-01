package dev.cinder.plugin.command;

import dev.cinder.server.CinderScheduler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Command API for plugins.
 *
 * Registered commands always execute on the tick thread via scheduler sync queue.
 */
public final class CinderCommandRegistry {

    private static final Logger LOG = Logger.getLogger("cinder.plugin.commands");

    private final CinderScheduler scheduler;
    private final Map<String, CinderCommand> commands = new ConcurrentHashMap<>();

    public CinderCommandRegistry(CinderScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void register(String command, CinderCommand handler) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(handler, "handler");
        commands.put(normalize(command), handler);
    }

    public void unregister(String command) {
        commands.remove(normalize(command));
    }

    public boolean execute(String command, String sender, List<String> args) {
        CinderCommand handler = commands.get(normalize(command));
        if (handler == null) {
            return false;
        }

        scheduler.submitSync("plugin-command:" + command, () -> {
            try {
                handler.execute(new CinderCommandContext(sender, List.copyOf(args)));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[PluginCommands] Command failed: " + command, e);
            }
        });

        return true;
    }

    public int size() {
        return commands.size();
    }

    private static String normalize(String command) {
        return command.trim().toLowerCase();
    }
}
