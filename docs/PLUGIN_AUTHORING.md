# Plugin Authoring Guide

This guide covers the plugin API under `dev.cinder.plugin` in this repository.

Important runtime note:

- Cinder OS v1.0.0 runs PaperMC in production (`/opt/cinder/server/paper-server.jar`).
- The API in this document targets the Cinder Java server track in this repo (tests, local runs, and engine experiments).

---

## 1. Requirements

- Java 21
- A build that can compile against this repository's classes
- ServiceLoader descriptor at:
  - `META-INF/services/dev.cinder.plugin.CinderPlugin`

---

## 2. Minimal Plugin

```java
package example;

import dev.cinder.plugin.CinderPlugin;
import dev.cinder.plugin.CinderPluginContext;
import dev.cinder.plugin.events.TickEvent;

public final class ExamplePlugin implements CinderPlugin {

	@Override
	public String getName() {
		return "example-plugin";
	}

	@Override
	public void onEnable(CinderPluginContext context) {
		context.eventBus().register(TickEvent.class, event -> {
			if (event.tick() % 200 == 0) {
				System.out.println("[example-plugin] tick=" + event.tick());
			}
		});

		context.commandRegistry().register("hello", cmd ->
			System.out.println("[example-plugin] hello from " + cmd.sender())
		);
	}
}
```

ServiceLoader file contents (`META-INF/services/dev.cinder.plugin.CinderPlugin`):

```text
example.ExamplePlugin
```

---

## 3. API Surface

`CinderPlugin` lifecycle:

- `String getName()`
- `onEnable(CinderPluginContext context)`
- `onReload(CinderPluginContext context)`
- `onDisable()`

`CinderPluginContext` accessors:

- `scheduler()` for sync dispatch integration
- `eventBus()` for typed event subscriptions
- `commandRegistry()` for command registration
- `pluginDataDirectory()` for plugin-local state

Built-in events:

- `PlayerJoinEvent`
- `PlayerLeaveEvent`
- `EntitySpawnEvent`
- `ChunkLoadEvent`
- `TickEvent`

Command handling:

- Register with `context.commandRegistry().register("name", handler)`
- Registered handlers execute through scheduler sync dispatch

---

## 4. Packaging and Deployment

1. Build plugin JAR with compiled class files and ServiceLoader descriptor.
2. Place JAR in plugin directory used by the Cinder Java server process.

Common paths used by this project:

- Plugin JARs: `/opt/cinder/plugins`
- Plugin data: `/opt/cinder/plugin-data/<plugin-name>`

If you deploy through USB import on Cinder OS:

1. Put plugin JAR in `cinder-import/plugins/` on USB media.
2. Ensure hash is permitted when allowlists are enabled:
   - Global allowlist: `/opt/cinder/config/import-allowlist.sha256`
   - Plugin allowlist: `/opt/cinder/config/plugin-import-allowlist.sha256`
3. Run import: `/opt/cinder/scripts/usb-import.sh`

---

## 5. Safety and Failure Model

- Plugin loading uses one classloader per plugin JAR.
- Event listener exceptions are caught and logged.
- Command handler exceptions are caught and logged.
- Shutdown and reload failures are isolated per plugin.

You should still treat plugin code as production code:

- Keep allocations low in high-frequency callbacks (especially tick listeners).
- Avoid blocking I/O in event handlers.
- Write to `pluginDataDirectory()` only.

---

## 6. Compatibility Guidance

This API is versioned with the repository, not with Bukkit/Paper APIs.

- Do not assume Paper/Bukkit compatibility.
- Pin the Cinder version you built against.
- Re-test plugin behavior after runtime or scheduler changes.
