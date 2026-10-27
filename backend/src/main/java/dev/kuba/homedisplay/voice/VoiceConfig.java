package dev.kuba.homedisplay.voice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class VoiceConfig {

    /** Claude when an API key is configured, otherwise a stand-in that reports the assistant as not set up. */
    @Bean
    AgentModel agentModel(AppProperties props, JsonMapper json) {
        AppProperties.Voice voice = props.voice();
        String key = voice.apiKey();
        return key == null || key.isBlank() ? new UnavailableAgentModel(voice.model()) : new AnthropicAgentModel(voice, json);
    }
}
