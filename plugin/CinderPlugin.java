package dev.cinder.plugin;

/**
 * Base contract for Cinder plugins.
 *
 * Plugins are discovered via Java ServiceLoader from plugin JARs.
 */
public interface CinderPlugin {

    /** Stable plugin identifier used for logging and data directory naming. */
    String getName();

    /** Called when the plugin is loaded and enabled. */
    default void onEnable(CinderPluginContext context) throws Exception {
    }

    /** Called during server shutdown or plugin disable. */
    default void onDisable() throws Exception {
    }

    /** Called when plugin runtime reload is requested. */
    default void onReload(CinderPluginContext context) throws Exception {
    }
}
