package dev.kuba.homedisplay.devices.agent;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.kuba.homedisplay.config.AppProperties;
import dev.kuba.homedisplay.devices.Capabilities;
import dev.kuba.homedisplay.devices.ChannelInfo;
import dev.kuba.homedisplay.devices.ChannelState;
import dev.kuba.homedisplay.devices.DeviceException;
import dev.kuba.homedisplay.devices.DevicesDiscoveredEvent;
import dev.kuba.homedisplay.devices.Range;
import dev.kuba.homedisplay.stream.ChangePublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server end of the Pi agent's outbound WebSocket (PRD section 6, "Agent protocol"). It accepts
 * one agent at a time, records the devices and states the agent reports, and sends commands and
 * waits for their results. It knows nothing about device protocols.
 */
@Component
@ConditionalOnProperty(name = "app.devices.mode", havingValue = "agent")
public class AgentGateway extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentGateway.class);
    static final int PROTOCOL_VERSION = 1;
    /** The agent pings every ~20 s; silence for this long means the link is dead. */
    static final Duration SILENCE_LIMIT = Duration.ofSeconds(60);

    private final AgentRegistry registry;
    private final ChangePublisher changes;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration commandTimeout;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();
    private volatile WebSocketSession current;
    private volatile Instant lastSeen = Instant.EPOCH;

    public AgentGateway(AgentRegistry registry, ChangePublisher changes, ApplicationEventPublisher events,
            JsonMapper json, Clock clock, AppProperties props) {
        this.registry = registry;
        this.changes = changes;
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.commandTimeout = props.devices().commandTimeout();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession previous = current;
        current = session;
        lastSeen = clock.instant();
        if (previous != null && previous.isOpen()) {
            log.info("A new agent connection replaces the old one");
            previous.close(new CloseStatus(4000, "replaced by a newer connection"));
        }
        // Devices become "online" only once the agent has said hello.
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (session != current) {
            return;
        }
        lastSeen = clock.instant();
        JsonNode node;
        try {
            node = json.readTree(message.getPayload());
        } catch (JacksonException e) {
            log.warn("Ignoring unreadable agent message");
            return;
        }
        String type = node.path("type").asString("");
        switch (type) {
            case "hello" -> onHello(node);
            case "devices" -> onDevices(node);
            case "state" -> onState(node);
            case "result" -> onResult(node);
            case "ping" -> send(session, json.createObjectNode().put("v", PROTOCOL_VERSION).put("type", "pong"));
            case "pong" -> { /* heartbeat answer, nothing to do */ }
            default -> log.debug("Ignoring unknown agent message type '{}'", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session != current) {
            return; // an old connection that was replaced
        }
        current = null;
        goOffline("connection closed");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Agent transport error: {}", exception.toString());
    }

    /** Drops an agent that has gone silent (for example because the Pi lost power). */
    @Scheduled(fixedDelay = 15_000)
    void checkHeartbeat() {
        WebSocketSession session = current;
        if (session != null && Duration.between(lastSeen, clock.instant()).compareTo(SILENCE_LIMIT) > 0) {
            log.warn("Agent silent for over {} s, dropping the connection", SILENCE_LIMIT.toSeconds());
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                // closing a dead socket may fail; state is reset below either way
            }
            if (current == session) {
                current = null;
                goOffline("heartbeat lost");
            }
        }
    }

    public boolean connected() {
        WebSocketSession session = current;
        return session != null && session.isOpen() && registry.online();
    }

    /**
     * Sends a command and waits for its result.
     *
     * @return the agent's result message
     * @throws DeviceException if the agent is offline, does not answer in time, or reports failure
     */
    public JsonNode sendCommand(String deviceId, String action, ObjectNode args) {
        return sendCommand(deviceId, action, null, args);
    }

    /** As {@link #sendCommand(String, String, ObjectNode)}, for an action on one channel of the device. */
    public JsonNode sendCommand(String deviceId, String action, String channel, ObjectNode args) {
        WebSocketSession session = current;
        if (session == null || !session.isOpen() || !registry.online()) {
            throw DeviceException.offline();
        }
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ObjectNode command = json.createObjectNode()
                    .put("v", PROTOCOL_VERSION)
                    .put("type", "command")
                    .put("id", id)
                    .put("deviceId", deviceId)
                    .put("action", action);
            if (channel != null) {
                command.put("channel", channel);
            }
            command.set("args", args);
            send(session, command);
            JsonNode result = future.get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!result.path("ok").asBoolean(false)) {
                throw DeviceException.commandFailed(result.path("error").asString("The device did not respond"));
            }
            return result;
        } catch (TimeoutException e) {
            throw DeviceException.commandFailed("The home agent did not answer in time");
        } catch (ExecutionException e) {
            throw DeviceException.offline();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw DeviceException.commandFailed("Interrupted");
        } finally {
            pending.remove(id);
        }
    }

    private void onHello(JsonNode node) {
        registry.replaceDevices(parseDevices(node.path("devices")), clock.instant());
        applyStates(node.path("states"));
        registry.setOnline(true);
        log.info("Agent {} connected with {} devices", node.path("agentId").asString("?"),
                registry.list().size());
        events.publishEvent(DevicesDiscoveredEvent.from(registry.list()));
        changes.publish(ChangePublisher.DEVICES);
    }

    private void onDevices(JsonNode node) {
        registry.replaceDevices(parseDevices(node.path("devices")), clock.instant());
        applyStates(node.path("states"));
        events.publishEvent(DevicesDiscoveredEvent.from(registry.list()));
        changes.publish(ChangePublisher.DEVICES);
    }

    private void onState(JsonNode node) {
        if (applyState(node)) {
            changes.publish(ChangePublisher.DEVICES);
        }
    }

    private void onResult(JsonNode node) {
        CompletableFuture<JsonNode> future = pending.get(node.path("id").asString(""));
        if (future != null) {
            future.complete(node);
        }
    }

    private List<AgentRegistry.DeviceInfo> parseDevices(JsonNode devices) {
        List<AgentRegistry.DeviceInfo> infos = new ArrayList<>();
        for (JsonNode d : devices) {
            String id = d.path("deviceId").asString("");
            if (!id.isBlank()) {
                infos.add(new AgentRegistry.DeviceInfo(id, d.path("name").asString(id), d.path("type").asString("unknown"),
                        parseChannels(d.path("channels"))));
            }
        }
        return infos;
    }

    /** Channels with their capabilities; an agent that sends none gets a single power-only channel. */
    private static List<ChannelInfo> parseChannels(JsonNode channels) {
        List<ChannelInfo> out = new ArrayList<>();
        for (JsonNode c : channels) {
            String id = c.path("id").asString("");
            if (id.isBlank()) {
                continue;
            }
            JsonNode caps = c.path("capabilities");
            JsonNode temp = caps.path("colorTemp");
            Range range = temp.isObject() ? new Range(temp.path("min").asInt(2700), temp.path("max").asInt(6500)) : null;
            out.add(new ChannelInfo(id, c.path("label").asString(id),
                    new Capabilities(caps.path("brightness").asBoolean(false), range, caps.path("color").asBoolean(false))));
        }
        return out;
    }

    private static List<ChannelState> parseChannelStates(JsonNode channels) {
        List<ChannelState> out = new ArrayList<>();
        for (JsonNode c : channels) {
            String id = c.path("id").asString("");
            if (!id.isBlank()) {
                out.add(new ChannelState(id, c.path("on").asBoolean(false), number(c, "brightness"), number(c, "colorTemp"),
                        number(c, "hue"), number(c, "saturation"),
                        c.hasNonNull("colorMode") ? c.get("colorMode").asString() : null));
            }
        }
        return out;
    }

    private static Integer number(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private void applyStates(JsonNode states) {
        for (JsonNode s : states) {
            applyState(s);
        }
    }

    /** Applies a state item from a hello, state message or command result. Returns whether anything changed. */
    boolean applyState(JsonNode s) {
        Instant at = s.hasNonNull("updatedAt") ? parseInstant(s.get("updatedAt").asString(), clock.instant())
                : clock.instant();
        return registry.updateState(s.path("deviceId").asString(""), s.path("power").asBoolean(false),
                s.path("reachable").asBoolean(true), parseChannelStates(s.path("channels")), at);
    }

    private static Instant parseInstant(String text, Instant fallback) {
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void goOffline(String reason) {
        boolean wasOnline = registry.online();
        registry.setOnline(false);
        pending.values().forEach(f -> f.completeExceptionally(new IOException("agent offline")));
        log.info("Agent offline ({})", reason);
        if (wasOnline) {
            changes.publish(ChangePublisher.DEVICES);
        }
    }

    private void send(WebSocketSession session, JsonNode message) {
        try {
            synchronized (sendLock) {
                session.sendMessage(new TextMessage(json.writeValueAsString(message)));
            }
        } catch (IOException | IllegalStateException e) {
            throw DeviceException.offline();
        }
    }
}
