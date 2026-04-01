package dev.cinder.plugin.command;

@FunctionalInterface
public interface CinderCommand {
    void execute(CinderCommandContext context) throws Exception;
}
