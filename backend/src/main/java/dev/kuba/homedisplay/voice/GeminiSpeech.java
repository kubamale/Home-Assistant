package dev.kuba.homedisplay.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Listening and speaking through Google's Gemini API, by plain HTTPS.
 *
 * <ul>
 * <li>Listening: {@code generateContent} on the transcription model, with the recording inline as a WAV and the
 * wanted languages and the home's own names as hints.</li>
 * <li>Speaking: the Interactions API on a text-to-speech model. Streaming delivers raw 24 kHz PCM as it is made;
 * the non-streaming form is kept as a fallback.</li>
 * </ul>
 *
 * Every error becomes a {@link SpeechException} with a message that is safe to show; the key travels only in a
 * header and is never logged.
 */
class GeminiSpeech implements SpeechBackend {

    private static final Logger log = LoggerFactory.getLogger(GeminiSpeech.class);
    private static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com";
    /** A single pushed chunk stays small so the listener starts hearing audio early. */
    private static final int SLICE = 9600;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String base;
    private final String key;
    private final AppProperties.Speech config;

    GeminiSpeech(AppProperties.Speech config, JsonMapper json) {
        this.config = config;
        this.json = json;
        this.key = config.geminiApiKey();
        String configured = config.baseUrl();
        this.base = configured == null || configured.isBlank() ? DEFAULT_BASE : configured.replaceAll("/+$", "");
    }

    @Override
    public boolean available() {
        return true;
    }

    // ---- listening ----

    @Override
    public String transcribe(byte[] pcm, List<String> languages, List<String> vocabulary) {
        ObjectNode body = json.createObjectNode();
        ObjectNode part = body.putArray("contents").addObject().putArray("parts").addObject();
        part.putObject("inlineData").put("mimeType", "audio/wav")
                .put("data", Base64.getEncoder().encodeToString(Pcm.wav(pcm, SpeechToText.SAMPLE_RATE)));
        ObjectNode transcription = body.putObject("generationConfig").putObject("audioTranscriptionConfig");
        ArrayNode codes = transcription.putArray("languageCodes");
        languages.forEach(codes::add);
        transcription.put("mode", config.transcribeMode());
        if (!vocabulary.isEmpty()) {
            ArrayNode terms = transcription.putArray("customVocabulary");
            vocabulary.stream().limit(100).forEach(terms::add);
        }

        HttpResponse<String> response = send("/v1beta/models/" + config.transcribeModel() + ":generateContent", body,
                HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(30));
        check(response.statusCode(), response.body());
        JsonNode reply = parse(response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode candidatePart : reply.path("candidates").path(0).path("content").path("parts")) {
            // The transcription model answers with {"audioTranscription": {"text": ...}}; a plain text part is accepted too.
            JsonNode piece = candidatePart.hasNonNull("audioTranscription") ? candidatePart.get("audioTranscription") : candidatePart;
            if (piece.hasNonNull("text")) {
                text.append(piece.get("text").asString());
            }
        }
        return text.toString().trim();
    }

    @Override
    public Live listenLive(List<String> languages, List<String> vocabulary) {
        if (!config.liveTranscription()) {
            return null;
        }
        String socketBase = base.replaceFirst("^http", "ws");
        URI uri = URI.create(socketBase + "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent");
        return new GeminiLiveListening(http, uri, key, config.liveTranscribeModel(), config.transcribeMode(), languages, vocabulary,
                Duration.ofSeconds(config.maxUtteranceSeconds() + 10L), Duration.ofSeconds(4), json);
    }

    // ---- speaking ----

    @Override
    public void speak(String text, String voice, String style, AudioSink sink) {
        ObjectNode body = json.createObjectNode().put("model", config.ttsModel());
        ObjectNode content = body.putArray("input").addObject().put("type", "user_input").putArray("content").addObject();
        content.put("type", "text").put("text", text);
        if (style != null && !style.isBlank()) {
            content.putArray("annotations").addObject().put("type", "speech_metadata").put("style", style.trim());
        }
        ObjectNode format = body.putObject("response_format").put("type", "audio");
        body.putObject("generation_config").putArray("speech_config").addObject().put("voice", voice);
        if (config.ttsStreaming()) {
            body.put("stream", true);
            streamSpeech(body, sink);
        } else {
            format.put("mime_type", "audio/l16");
            wholeSpeech(body, sink);
        }
    }

    private void streamSpeech(ObjectNode body, AudioSink sink) {
        HttpResponse<InputStream> response = send("/v1beta/interactions", body, HttpResponse.BodyHandlers.ofInputStream(),
                Duration.ofSeconds(60));
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                check(response.statusCode(), new String(in.readNBytes(2000), StandardCharsets.UTF_8));
            }
            Pcm.Aligner aligner = new Pcm.Aligner();
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean heardAudio = false;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                byte[] audio = audioIn(line);
                if (audio == null) {
                    continue;
                }
                byte[] aligned = aligner.push(audio);
                if (aligned.length == 0) {
                    continue;
                }
                heardAudio = true;
                if (!sink.accept(aligned)) {
                    return; // closing the stream below cancels the request
                }
            }
            if (!heardAudio) {
                throw new SpeechException("The speech service returned no audio");
            }
        } catch (IOException e) {
            throw new SpeechException("The speech service could not be reached", e);
        }
    }

    private void wholeSpeech(ObjectNode body, AudioSink sink) {
        HttpResponse<String> response = send("/v1beta/interactions", body, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(60));
        check(response.statusCode(), response.body());
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (JsonNode step : parse(response.body()).path("steps")) {
            for (JsonNode part : step.path("content")) {
                if ("audio".equals(part.path("type").asString("")) && part.hasNonNull("data")) {
                    all.writeBytes(Base64.getDecoder().decode(part.get("data").asString()));
                }
            }
        }
        byte[] pcm = all.toByteArray();
        if (pcm.length == 0) {
            throw new SpeechException("The speech service returned no audio");
        }
        for (int at = 0; at < pcm.length; at += SLICE) {
            if (!sink.accept(java.util.Arrays.copyOfRange(pcm, at, Math.min(pcm.length, at + SLICE) & ~1))) {
                return;
            }
        }
    }

    /** The audio in one line of the streamed response, or null when the line carries none. */
    private byte[] audioIn(String line) {
        String data = line.strip();
        if (data.startsWith("data:")) {
            data = data.substring(5).strip();
        } else if (!data.startsWith("{")) {
            return null; // "event:" lines, comments and blank separators
        }
        if (data.isEmpty() || data.equals("[DONE]")) {
            return null;
        }
        try {
            JsonNode event = json.readTree(data);
            JsonNode delta = event.path("delta");
            // Chunks arrive as {"mime_type": "audio/l16", "data": ...}; a "type": "audio" marker is accepted as well.
            boolean audio = "audio".equals(delta.path("type").asString("")) || delta.path("mime_type").asString("").startsWith("audio/");
            if (audio && delta.hasNonNull("data")) {
                return Base64.getDecoder().decode(delta.get("data").asString());
            }
        } catch (JacksonException | IllegalArgumentException e) {
            log.debug("Ignoring an unreadable line of the speech stream");
        }
        return null;
    }

    // ---- http ----

    private <T> HttpResponse<T> send(String path, ObjectNode body, HttpResponse.BodyHandler<T> handler, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("x-goog-api-key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new SpeechException("The speech service could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechException("Interrupted", e);
        }
    }

    private void check(int status, String body) {
        if (status == 200) {
            return;
        }
        // Google's message says what was wrong with the request; it does not contain the key.
        log.warn("Gemini answered {}: {}", status, body.length() > 500 ? body.substring(0, 500) : body);
        throw switch (status) {
            case 401, 403 -> new SpeechException("The Gemini API key was rejected");
            case 429 -> new SpeechException("The speech service is busy, try again in a moment");
            default -> new SpeechException("The speech service refused the request (" + status + ")");
        };
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new SpeechException("The speech service sent an unreadable answer", e);
        }
    }
}
