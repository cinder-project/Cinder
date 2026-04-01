# Plugin Authoring Guide

This guide describes the minimal Cinder plugin API implemented in Phase 8.

## Packaging

Plugins are loaded via Java ServiceLoader.

1. Implement [dev.cinder.plugin.CinderPlugin](../plugin/CinderPlugin.java).
2. Add `META-INF/services/dev.cinder.plugin.CinderPlugin` in your plugin JAR.
3. Put the plugin JAR in the server plugin directory (default: `plugins/`).

## Lifecycle

- `onEnable(context)` runs when the plugin is loaded.
- `onReload(context)` runs when plugin reload is requested.
- `onDisable()` runs during shutdown or disable.

## Event Bus

Use `context.eventBus().register(EventType.class, listener)` to subscribe.

Built-in event classes:

- `PlayerJoinEvent`
- `PlayerLeaveEvent`
- `EntitySpawnEvent`
- `ChunkLoadEvent`
- `TickEvent`

## Commands

Use `context.commandRegistry().register("mycommand", handler)`.

Handlers execute on the tick thread through scheduler sync dispatch.

## Plugin Data Directory

Each plugin gets a dedicated data path:

- `context.pluginDataDirectory()`

Use this directory for plugin config and local state.

## Safety

- Plugin code exceptions are caught and logged by the loader/event bus.
- Each plugin JAR is loaded in its own URLClassLoader.
- A plugin disable failure does not stop server shutdown.
