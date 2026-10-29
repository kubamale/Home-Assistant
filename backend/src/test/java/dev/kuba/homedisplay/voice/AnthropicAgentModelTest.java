package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.sun.net.httpserver.HttpServer;

import dev.kuba.homedisplay.config.AppProperties;
import dev.kuba.homedisplay.voice.AgentModel.AgentSession;
import dev.kuba.homedisplay.voice.AgentModel.Step;
import dev.kuba.homedisplay.voice.AgentModel.ToolResult;
import dev.kuba.homedisplay.voice.AgentModel.ToolSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Claude adapter against a small local server that speaks the Messages API: it checks the request the
 * adapter builds (model, system prompt, tools, effort), that tool calls are read from the reply, and that the
 * model's own reply and the tool results are sent back on the next request.
 */
class AnthropicAgentModelTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final List<String> replies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            String body = status == 200 ? replies.remove(0) : "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicAgentModel model() {
        return new AnthropicAgentModel(new AppProperties.Voice("test-key", "claude-haiku-5-5", 2048, Duration.ofMinutes(2), 12, 6, 20, 30, true,
                "http://127.0.0.1:" + server.getAddress().getPort()), json);
    }

    private static String message(String content, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\",\"content\":" + content
                + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    private static final ToolSpec LIGHTS = new ToolSpec("get_lights", "Read the lights.",
            Map.of("room", Map.of("type", "string", "description", "Optional room")), List.of());

    @Test
    void aToolCallIsReadFromTheReplyAndTheExchangeIsSentBack() {
        replies.add(message("[{\"type\":\"text\",\"text\":\"Let me look.\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_lights\",\"input\":{\"room\":\"Kitchen\"}}]", "tool_use"));
        replies.add(message("[{\"type\":\"text\",\"text\":\"The kitchen light is on.\"}]", "end_turn"));

        AgentSession session = model().start("You are the assistant.", List.of(LIGHTS));
        Step first = session.send("is the kitchen light on?");

        assertTrue(first.wantsTools());
        assertEquals("get_lights", first.toolCalls().get(0).name());
        assertEquals("toolu_1", first.toolCalls().get(0).id());
        assertEquals("Kitchen", first.toolCalls().get(0).input().path("room").asString());
        assertFalse(first.refused() || first.truncated());

        Step second = session.sendResults(List.of(new ToolResult("toolu_1", "{\"lights\":[]}", false)));
        assertEquals("The kitchen light is on.", second.text());
        assertFalse(second.wantsTools());

        // the first request: who we talk to, what we say, which tools, how hard to think
        JsonNode one = requests.get(0);
        assertEquals("claude-haiku-5-5", one.path("model").asString());
        assertEquals(2048, one.path("max_tokens").asInt());
        assertEquals("You are the assistant.", one.path("system").asString());
        assertEquals("low", one.path("output_config").path("effort").asString());
        assertEquals("get_lights", one.path("tools").get(0).path("name").asString());
        assertEquals("string", one.path("tools").get(0).path("input_schema").path("properties").path("room").path("type").asString());
        assertEquals("user", one.path("messages").get(0).path("role").asString());
        assertEquals("is the kitchen light on?", one.path("messages").get(0).path("content").asString());
        assertEquals("test-key", apiKeys.get(0));

        // the second request replays the conversation so far: the question, the model's own tool call, our result
        JsonNode messages = requests.get(1).path("messages");
        assertEquals(3, messages.size());
        assertEquals("assistant", messages.get(1).path("role").asString());
        assertEquals("tool_use", messages.get(1).path("content").get(1).path("type").asString());
        assertEquals("toolu_1", messages.get(1).path("content").get(1).path("id").asString());
        assertEquals("user", messages.get(2).path("role").asString());
        JsonNode result = messages.get(2).path("content").get(0);
        assertEquals("tool_result", result.path("type").asString());
        assertEquals("toolu_1", result.path("tool_use_id").asString());
        assertEquals("{\"lights\":[]}", result.path("content").asString());
    }

    @Test
    void stopReasonsAreReported() {
        replies.add(message("[]", "refusal"));
        replies.add(message("[{\"type\":\"text\",\"text\":\"Half an ans\"}]", "max_tokens"));

        AgentSession session = model().start("system", List.of());
        assertTrue(session.send("a").refused());
        Step cut = session.send("b");
        assertTrue(cut.truncated());
        assertEquals("Half an ans", cut.text());
    }

    @Test
    void aRejectedRequestBecomesAFriendlyErrorWithoutLeakingTheKey() {
        status = 401;

        VoiceException e = assertThrows(VoiceException.class, () -> model().start("system", List.of()).send("hi"));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertFalse(String.valueOf(e.getReason()).contains("test-key"));
    }

    @Test
    void theUnavailableModelSaysSoWhenAskedToStart() {
        AgentModel off = new UnavailableAgentModel("claude-haiku-5-5");
        assertFalse(off.available());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(VoiceException.class, () -> off.start("s", new ArrayList<>())).getStatusCode());
    }
}
