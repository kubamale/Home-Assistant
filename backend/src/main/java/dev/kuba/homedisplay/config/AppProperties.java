package dev.kuba.homedisplay.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(Auth auth, Weather weather, Calendar calendar, Devices devices, Voice voice, Speech speech) {

    public record Auth(
            String passwordHash,
            String password,
            @DefaultValue("90") int sessionDays,
            @DefaultValue("true") boolean secureCookies) {
    }

    public record Weather(
            @DefaultValue("https://api.open-meteo.com/v1/forecast") String forecastUrl,
            @DefaultValue("https://geocoding-api.open-meteo.com/v1/search") String geocodingUrl,
            @DefaultValue("15m") Duration refreshInterval) {

        /** Cached data older than this is flagged stale for clients. */
        public Duration staleAfter() {
            return refreshInterval.multipliedBy(2);
        }
    }

    /** {@code mode} is off, fake or caldav. The caldav settings are only needed in caldav mode. */
    public record Calendar(@DefaultValue("off") String mode, CalDav caldav) {

        public record CalDav(
                @DefaultValue("https://caldav.icloud.com/") String url,
                String username,
                String password,
                /** Only show calendars with these names; empty means all of them. */
                @DefaultValue List<String> calendars) {
        }
    }

    public record Devices(@DefaultValue("fake") String mode, @DefaultValue("5s") Duration commandTimeout) {
    }

    /**
     * The text assistant. It is available when an Anthropic API key is set; without one the endpoint answers
     * 503 and the rest of the app is unaffected.
     */
    public record Voice(
            String apiKey,
            @DefaultValue("claude-haiku-5-5") String model,
            @DefaultValue("1024") long maxTokens,
            /** A conversation that has been quiet this long is forgotten, so "and dim it more" only works soon after. */
            @DefaultValue("2m") Duration conversationIdle,
            /** Questions in one conversation before a fresh one is started. The history is only ever appended to. */
            @DefaultValue("12") int maxTurns,
            /** Model calls allowed within one question (the model may call several tools in sequence). */
            @DefaultValue("6") int maxToolRounds,
            @DefaultValue("20") int requestsPerMinute,
            @DefaultValue("30") int historyDays,
            /** Plain commands ("turn off the background light") are done without asking the model, which is quicker. */
            @DefaultValue("true") boolean quickCommands,
            /** Only for tests or a proxy; empty means Anthropic's own address. */
            String baseUrl) {
    }

    /**
     * Listening and speaking, through Google's Gemini API. Without a key the microphone is switched off in the
     * app and the typed assistant keeps working.
     */
    public record Speech(
            String geminiApiKey,
            @DefaultValue("gemini-3.5-transcribe") String transcribeModel,
            @DefaultValue("SMART") String transcribeMode,
            @DefaultValue("gemini-3.8-flash-tts") String ttsModel,
            /** Stream the audio as it is made; when off, each sentence is fetched whole (slower to start). */
            @DefaultValue("true") boolean ttsStreaming,
            /** The model that hears a sentence while it is still being spoken. */
            @DefaultValue("gemini-3.5-transcribe-live") String liveTranscribeModel,
            /** Hear the sentence while it is spoken, which saves about a second; when off, or if it fails, the recording is sent whole. */
            @DefaultValue("true") boolean liveTranscription,
            /** Also transcribe each sentence the slow way and log both texts, to judge the live one. Costs a second call. */
            @DefaultValue("false") boolean compareTranscripts,
            /** The longest single utterance accepted from a microphone. */
            @DefaultValue("15") int maxUtteranceSeconds,
            /** Make the fixed short answers ("Done.") ahead of time, when a microphone page connects. */
            @DefaultValue("true") boolean warmPhrases,
            /** Only for tests or a proxy; empty means Google's own address. */
            String baseUrl) {
    }
}
