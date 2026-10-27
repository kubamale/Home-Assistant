package dev.kuba.homedisplay.voice;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude through the Messages API, with a hand-written tool loop (the caller runs the tools, because they
 * are the home's own services). The assistant answers short commands, so it asks for low effort, and it
 * hands back the model's whole reply on every request, thinking blocks included, which keeps the history
 * valid however the model uses thinking.
 */
class AnthropicAgentModel implements AgentModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAgentModel.class);

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final JsonMapper json;

    AnthropicAgentModel(AppProperties.Voice config, JsonMapper json) {
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        this.client = builder.build();
        this.model = config.model();
        this.maxTokens = config.maxTokens();
        this.json = json;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public AgentSession start(String system, List<ToolSpec> tools) {
        return new Session(system, tools.stream().map(AnthropicAgentModel::toTool).toList());
    }

    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(spec.required()).build())
                .build();
    }

    private final class Session implements AgentSession {

        private final String system;
        private final List<Tool> tools;
        private final List<MessageParam> history = new ArrayList<>();

        Session(String system, List<Tool> tools) {
            this.system = system;
            this.tools = tools;
        }

        @Override
        public Step send(String userText) {
            history.add(MessageParam.builder().role(MessageParam.Role.USER).content(userText).build());
            return call();
        }

        @Override
        public Step sendResults(List<ToolResult> results) {
            List<ContentBlockParam> blocks = results.stream()
                    .map(r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(r.toolCallId()).content(r.content()).isError(r.error()).build()))
                    .toList();
            history.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build());
            return call();
        }

        private Step call() {
            MessageCreateParams.Builder request = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(system)
                    .messages(List.copyOf(history))
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
            tools.forEach(request::addTool);
            Message reply;
            try {
                reply = client.messages().create(request.build());
            } catch (AnthropicException e) {
                // The message can name the request; it never contains the API key.
                log.warn("Anthropic request failed: {}", e.toString());
                throw VoiceException.unavailable();
            }
            history.add(reply.toParam());
            return toStep(reply);
        }

        private Step toStep(Message reply) {
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (ContentBlock block : reply.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
                block.toolUse().ifPresent(use -> calls.add(new ToolCall(use.id(), use.name(), toNode(use._input()))));
            }
            StopReason stop = reply.stopReason().orElse(StopReason.END_TURN);
            return new Step(text.toString().trim(), calls, StopReason.REFUSAL.equals(stop), StopReason.MAX_TOKENS.equals(stop));
        }

        private tools.jackson.databind.JsonNode toNode(JsonValue input) {
            return json.valueToTree(input.convert(Map.class));
        }
    }
}
