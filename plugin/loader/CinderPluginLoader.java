package dev.cinder.plugin.loader;

import dev.cinder.plugin.CinderEventBus;
import dev.cinder.plugin.CinderPlugin;
import dev.cinder.plugin.CinderPluginContext;
import dev.cinder.plugin.command.CinderCommandRegistry;
import dev.cinder.server.CinderScheduler;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Isolated plugin loader: one classloader per plugin jar.
 */
public final class CinderPluginLoader {

    private static final Logger LOG = Logger.getLogger("cinder.plugin.loader");

    private final CinderScheduler scheduler;
    private final CinderEventBus eventBus;
    private final CinderCommandRegistry commandRegistry;
    private final Path pluginsDir;
    private final Path pluginDataDir;

    private final List<LoadedPlugin> loadedPlugins = new ArrayList<>();

    public CinderPluginLoader(
            CinderScheduler scheduler,
            CinderEventBus eventBus,
            CinderCommandRegistry commandRegistry,
            Path pluginsDir,
            Path pluginDataDir
    ) {
        this.scheduler = scheduler;
        this.eventBus = eventBus;
        this.commandRegistry = commandRegistry;
        this.pluginsDir = pluginsDir;
        this.pluginDataDir = pluginDataDir;
    }

    public void loadAll() {
        try {
            Files.createDirectories(pluginsDir);
            Files.createDirectories(pluginDataDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise plugin directories", e);
        }

        try (var stream = Files.list(pluginsDir)) {
            stream
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .forEach(this::loadJar);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[PluginLoader] Failed to list plugins directory", e);
        }

        LOG.info("[PluginLoader] Loaded plugins: " + loadedPlugins.size());
    }

    private void loadJar(Path jarPath) {
        URLClassLoader classLoader = null;
        try {
            URL jarUrl = jarPath.toUri().toURL();
            classLoader = new URLClassLoader(new URL[]{jarUrl}, CinderPlugin.class.getClassLoader());

            ServiceLoader<CinderPlugin> serviceLoader = ServiceLoader.load(CinderPlugin.class, classLoader);
            CinderPlugin plugin = serviceLoader.findFirst().orElse(null);
            if (plugin == null) {
                classLoader.close();
                LOG.warning("[PluginLoader] No CinderPlugin service in: " + jarPath);
                return;
            }

            String safeName = plugin.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            Path dataPath = pluginDataDir.resolve(safeName);
            Files.createDirectories(dataPath);

            CinderPluginContext context = new CinderPluginContext(
                scheduler,
                eventBus,
                commandRegistry,
                dataPath
            );
            plugin.onEnable(context);

            loadedPlugins.add(new LoadedPlugin(plugin, classLoader, context, jarPath));
            LOG.info("[PluginLoader] Enabled plugin: " + plugin.getName() + " from " + jarPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[PluginLoader] Failed to load plugin jar: " + jarPath, e);
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void reloadAll() {
        for (LoadedPlugin loaded : loadedPlugins) {
            try {
                loaded.plugin().onReload(loaded.context());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[PluginLoader] Reload failed for " + loaded.plugin().getName(), e);
            }
        }
    }

    public void shutdown() {
        for (LoadedPlugin loaded : loadedPlugins) {
            try {
                loaded.plugin().onDisable();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[PluginLoader] Disable failed for " + loaded.plugin().getName(), e);
            }
            try {
                loaded.classLoader().close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "[PluginLoader] Error closing classloader", e);
            }
        }
        loadedPlugins.clear();
    }

    public int loadedPluginCount() {
        return loadedPlugins.size();
    }

    private record LoadedPlugin(
        CinderPlugin plugin,
        URLClassLoader classLoader,
        CinderPluginContext context,
        Path jarPath
    ) {
    }
}
