package dev.kuba.homedisplay.voice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class SpeechConfig {

    /** Gemini when an API key is configured, otherwise a stand-in that reports voice as not set up. */
    @Bean
    SpeechBackend speech(AppProperties props, JsonMapper json) {
        String key = props.speech().geminiApiKey();
        return key == null || key.isBlank() ? new UnavailableSpeech() : new GeminiSpeech(props.speech(), json);
    }
}
