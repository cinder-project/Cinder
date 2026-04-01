package dev.cinder.plugin.events;

import dev.cinder.plugin.CinderEvent;

public record ChunkLoadEvent(int chunkX, int chunkZ) implements CinderEvent {
}
