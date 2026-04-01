package dev.cinder.plugin.events;

import dev.cinder.plugin.CinderEvent;

public record EntitySpawnEvent(long entityId, String entityType) implements CinderEvent {
}
