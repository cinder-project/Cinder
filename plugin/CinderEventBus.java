package dev.cinder.plugin;

import dev.cinder.server.CinderScheduler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async-safe event bus that dispatches plugin listeners on the tick thread.
 */
public final class CinderEventBus {

    private static final Logger LOG = Logger.getLogger("cinder.plugin.eventbus");

    private final CinderScheduler scheduler;
    private final Map<Class<? extends CinderEvent>, CopyOnWriteArrayList<CinderEventListener<?>>> listeners =
        new ConcurrentHashMap<>();

    public CinderEventBus(CinderScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public <T extends CinderEvent> void register(Class<T> eventType, CinderEventListener<T> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        listeners.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <T extends CinderEvent> void unregister(Class<T> eventType, CinderEventListener<T> listener) {
        List<CinderEventListener<?>> bucket = listeners.get(eventType);
        if (bucket != null) {
            bucket.remove(listener);
        }
    }

    public void publish(CinderEvent event) {
        Objects.requireNonNull(event, "event");
        scheduler.submitSync("plugin-event:" + event.getClass().getSimpleName(), () -> dispatch(event));
    }

    @SuppressWarnings("unchecked")
    private <T extends CinderEvent> void dispatch(T event) {
        List<CinderEventListener<?>> bucket = listeners.get(event.getClass());
        if (bucket == null || bucket.isEmpty()) {
            return;
        }

        for (CinderEventListener<?> raw : bucket) {
            try {
                ((CinderEventListener<T>) raw).onEvent(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                    "[PluginEventBus] Listener failed for " + event.getClass().getSimpleName(), e);
            }
        }
    }

    @FunctionalInterface
    public interface CinderEventListener<T extends CinderEvent> {
        void onEvent(T event) throws Exception;
    }
}
