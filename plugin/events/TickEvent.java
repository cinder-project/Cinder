package dev.cinder.plugin.events;

import dev.cinder.plugin.CinderEvent;

public record TickEvent(long tick) implements CinderEvent {
}
