package dev.kuba.homedisplay.voice;

import java.util.List;

/** Stands in for the model when no API key is configured. */
class UnavailableAgentModel implements AgentModel {

    private final String model;

    UnavailableAgentModel(String model) {
        this.model = model;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public AgentSession start(String system, List<ToolSpec> tools) {
        throw VoiceException.notConfigured();
    }
}
