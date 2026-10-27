package dev.kuba.homedisplay;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import dev.kuba.homedisplay.voice.AgentModel;

/**
 * A stand-in for Claude: what it answers is decided by the test. It records what the assistant gave it (the
 * system prompt, the tools, the sentences and the tool results) so tests can check those too.
 */
public class ScriptedAgentModel implements AgentModel {

    public volatile String system;
    public volatile List<ToolSpec> tools = List.of();
    public final AtomicInteger started = new AtomicInteger();
    public final List<String> userTexts = new CopyOnWriteArrayList<>();
    public final List<ToolResult> results = new CopyOnWriteArrayList<>();

    public volatile Function<String, Step> onUser = text -> Step.text("ok");
    public volatile Function<List<ToolResult>, Step> onResults = r -> Step.text("ok");

    public void reset() {
        system = null;
        tools = List.of();
        started.set(0);
        userTexts.clear();
        results.clear();
        onUser = text -> Step.text("ok");
        onResults = r -> Step.text("ok");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String modelName() {
        return "scripted";
    }

    @Override
    public AgentSession start(String system, List<ToolSpec> tools) {
        this.system = system;
        this.tools = tools;
        started.incrementAndGet();
        return new AgentSession() {
            @Override
            public Step send(String userText) {
                userTexts.add(userText);
                return onUser.apply(userText);
            }

            @Override
            public Step sendResults(List<ToolResult> batch) {
                results.addAll(batch);
                return onResults.apply(batch);
            }
        };
    }
}
