package dev.kuba.homedisplay.voice;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The language model behind the assistant, reduced to what the assistant needs: start a conversation with a
 * system prompt and some tools, then alternate between user messages and tool results. The real implementation
 * talks to Claude; tests use a scripted one.
 */
public interface AgentModel {

    /** False when no API key is configured, so the chat can say so instead of failing mysteriously. */
    boolean available();

    /** The name of the model, shown in the status endpoint. */
    String modelName();

    AgentSession start(String system, List<ToolSpec> tools);

    /**
     * One tool the model may call.
     *
     * @param properties the JSON Schema of each argument, by name
     */
    record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {
    }

    record ToolCall(String id, String name, JsonNode input) {
    }

    record ToolResult(String toolCallId, String content, boolean error) {
    }

    /**
     * What the model said. When {@code toolCalls} is not empty the model is waiting for their results.
     *
     * @param refused   the model declined to answer
     * @param truncated the answer was cut off because it ran out of room
     */
    record Step(String text, List<ToolCall> toolCalls, boolean refused, boolean truncated) {

        public static Step text(String text) {
            return new Step(text, List.of(), false, false);
        }

        public boolean wantsTools() {
            return !toolCalls.isEmpty();
        }
    }

    /** One conversation. It remembers everything said so far; history is only ever appended to. */
    interface AgentSession {

        Step send(String userText);

        Step sendResults(List<ToolResult> results);
    }
}
