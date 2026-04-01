package dev.cinder.plugin.command;

import java.util.List;

public record CinderCommandContext(String sender, List<String> args) {
}
