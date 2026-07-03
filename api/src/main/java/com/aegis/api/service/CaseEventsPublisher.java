package com.aegis.api.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events per case — the customer's page learns about a response
 * the moment an operator approves it, instead of polling every few seconds.
 *
 * <p>Emitters are keyed by tracking token (the customer's only credential).
 * A heartbeat every 20s keeps connections alive through proxies; dead
 * emitters are dropped on the first failed write.
 */
@Service
public class CaseEventsPublisher {

    private static final Logger log = LoggerFactory.getLogger(CaseEventsPublisher.class);
    private static final long TIMEOUT_MS = 30 * 60_000L;

    private final ConcurrentHashMap<String, List<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String trackingToken) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = listeners.computeIfAbsent(trackingToken,
                k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                listeners.remove(trackingToken, list);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ok\":true}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Notify every open page for this case that something changed. */
    public void publish(String trackingToken, String eventName) {
        if (trackingToken == null) {
            return;
        }
        List<SseEmitter> list = listeners.get(trackingToken);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(eventName)
                        .data(Map.of("event", eventName, "at", System.currentTimeMillis())));
            } catch (Exception ex) {
                e.completeWithError(ex);
            }
        }
        log.debug("SSE '{}' → {} listener(s)", eventName, list.size());
    }

    /** Keep-alive comment so proxies don't reap idle connections. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (List<SseEmitter> list : listeners.values()) {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().comment("hb"));
                } catch (Exception ex) {
                    e.completeWithError(ex);
                }
            }
        }
    }
}
