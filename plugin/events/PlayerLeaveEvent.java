package dev.cinder.plugin.events;

import dev.cinder.plugin.CinderEvent;

public record PlayerLeaveEvent(String playerName, long entityId) implements CinderEvent {
}
