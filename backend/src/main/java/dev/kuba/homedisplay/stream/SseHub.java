package dev.kuba.homedisplay.stream;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps the connected clients and broadcasts change signals. It holds no application state:
 * clients refetch the resource named by the event.
 */
@Component
public class SseHub implements ChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "ping");
        return emitter;
    }

    @Override
    public void publish(String event) {
        log.debug("publish {} to {} clients", event, emitters.size());
        for (SseEmitter emitter : emitters) {
            send(emitter, event);
        }
    }

    public int clientCount() {
        return emitters.size();
    }

    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        publish("ping");
    }

    private void send(SseEmitter emitter, String event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event).data("{}"));
            }
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
