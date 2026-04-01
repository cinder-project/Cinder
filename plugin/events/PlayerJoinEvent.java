package dev.cinder.plugin.events;

import dev.cinder.plugin.CinderEvent;

public record PlayerJoinEvent(String playerName, long entityId) implements CinderEvent {
}
