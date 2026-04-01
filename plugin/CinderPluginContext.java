package dev.cinder.plugin;

import dev.cinder.plugin.command.CinderCommandRegistry;
import dev.cinder.server.CinderScheduler;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable context passed to plugins during lifecycle callbacks. */
public final class CinderPluginContext {

    private final CinderScheduler scheduler;
    private final CinderEventBus eventBus;
    private final CinderCommandRegistry commandRegistry;
    private final Path pluginDataDirectory;

    public CinderPluginContext(
            CinderScheduler scheduler,
            CinderEventBus eventBus,
            CinderCommandRegistry commandRegistry,
            Path pluginDataDirectory
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry");
        this.pluginDataDirectory = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
    }

    public CinderScheduler scheduler() {
        return scheduler;
    }

    public CinderEventBus eventBus() {
        return eventBus;
    }

    public CinderCommandRegistry commandRegistry() {
        return commandRegistry;
    }

    public Path pluginDataDirectory() {
        return pluginDataDirectory;
    }
}
