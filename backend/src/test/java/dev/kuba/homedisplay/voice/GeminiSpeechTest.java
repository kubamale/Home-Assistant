package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Gemini client against a local server that answers in the documented shapes: the requests it builds, how
 * the streamed audio is read, and how failures are reported.
 */
class GeminiSpeechTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String reply = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            keys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            bodies.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiSpeech gemini(boolean streaming) {
        return new GeminiSpeech(new AppProperties.Speech("secret-key", "gemini-3.5-transcribe", "SMART", "gemini-3.8-flash-tts",
                streaming, "gemini-3.5-transcribe-live", true, false, 15, false, "http://127.0.0.1:" + server.getAddress().getPort()), json);
    }

    private static String b64(byte... bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    // ---- listening ----

    @Test
    void aRecordingIsSentInlineWithLanguageAndVocabularyHints() {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"audioTranscription\":{\"text\":\"  Turn off \"}},{\"audioTranscription\":{\"text\":\"the lamp.\\n\"}}]}}]}";
        byte[] pcm = new byte[3200];

        String text = gemini(true).transcribe(pcm, List.of("pl-PL", "en-US"), List.of("Desk lamp", "Living room"));

        assertEquals("Turn off the lamp.", text);
        assertEquals("/v1beta/models/gemini-3.5-transcribe:generateContent", paths.get(0));
        assertEquals("secret-key", keys.get(0));
        JsonNode body = bodies.get(0);
        JsonNode audio = body.path("contents").get(0).path("parts").get(0).path("inlineData");
        assertEquals("audio/wav", audio.path("mimeType").asString());
        byte[] wav = Base64.getDecoder().decode(audio.path("data").asString());
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(44 + pcm.length, wav.length);
        JsonNode config = body.path("generationConfig").path("audioTranscriptionConfig");
        assertEquals("pl-PL", config.path("languageCodes").get(0).asString());
        assertEquals("en-US", config.path("languageCodes").get(1).asString());
        assertEquals("SMART", config.path("mode").asString());
        assertEquals("Living room", config.path("customVocabulary").get(1).asString());
    }

    @Test
    void aPlainTextPartIsAlsoAccepted() {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Turn off the lamp.\"}]}}]}";
        assertEquals("Turn off the lamp.", gemini(true).transcribe(new byte[3200], List.of(), List.of()));
    }

    @Test
    void anEmptyTranscriptIsAnEmptyString() {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[]}}]}";
        assertEquals("", gemini(true).transcribe(new byte[3200], List.of(), List.of()));
        assertFalse(bodies.get(0).path("generationConfig").path("audioTranscriptionConfig").has("customVocabulary"));
    }

    @Test
    void failuresAreFriendlyAndNeverCarryTheKey() {
        status = 403;
        reply = "{\"error\":{\"message\":\"API key not valid\"}}";
        SpeechException rejected = assertThrows(SpeechException.class, () -> gemini(true).transcribe(new byte[3200], List.of(), List.of()));
        assertEquals("The Gemini API key was rejected", rejected.getMessage());

        status = 429;
        assertTrue(assertThrows(SpeechException.class, () -> gemini(true).transcribe(new byte[3200], List.of(), List.of()))
                .getMessage().contains("busy"));

        status = 500;
        SpeechException other = assertThrows(SpeechException.class, () -> gemini(true).transcribe(new byte[3200], List.of(), List.of()));
        assertTrue(other.getMessage().contains("500"));
        assertFalse(other.getMessage().contains("secret-key"));
    }

    // ---- speaking ----

    private static String event(String audioBase64) {
        return "event: step.delta\ndata: {\"event_type\":\"step.delta\",\"delta\":{\"mime_type\":\"audio/l16\",\"data\":\"" + audioBase64 + "\"}}\n\n";
    }

    @Test
    void streamedAudioIsHandedOverInWholeSamples() {
        contentType = "text/event-stream";
        // 5 bytes then 3 bytes: the first chunk ends in the middle of a sample
        reply = "event: step.start\ndata: {\"event_type\":\"step.start\"}\n\n" + event(b64(bytes(1, 2, 3, 4, 5)))
                + event(b64(bytes(6, 7, 8))) + "data: [DONE]\n\n";
        List<byte[]> received = new ArrayList<>();

        gemini(true).speak("Have a wonderful day!", "Kore", "cheerful and friendly", pcm -> {
            received.add(pcm);
            return true;
        });

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        received.forEach(chunk -> {
            assertEquals(0, chunk.length % 2, "a chunk was cut in the middle of a sample");
            all.writeBytes(chunk);
        });
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8), all.toByteArray());

        assertEquals("/v1beta/interactions", paths.get(0));
        assertEquals("secret-key", keys.get(0));
        JsonNode body = bodies.get(0);
        assertEquals("gemini-3.8-flash-tts", body.path("model").asString());
        assertTrue(body.path("stream").asBoolean());
        JsonNode input = body.path("input").get(0);
        assertEquals("user_input", input.path("type").asString());
        JsonNode content = input.path("content").get(0);
        assertEquals("text", content.path("type").asString());
        assertEquals("Have a wonderful day!", content.path("text").asString());
        assertEquals("speech_metadata", content.path("annotations").get(0).path("type").asString());
        assertEquals("cheerful and friendly", content.path("annotations").get(0).path("style").asString());
        assertEquals("audio", body.path("response_format").path("type").asString());
        assertEquals("Kore", body.path("generation_config").path("speech_config").get(0).path("voice").asString());
    }

    @Test
    void noStyleMeansNoAnnotation() {
        contentType = "text/event-stream";
        reply = event(b64(bytes(1, 2)));
        gemini(true).speak("Hi", "Puck", "  ", pcm -> true);
        assertFalse(bodies.get(0).path("input").get(0).path("content").get(0).has("annotations"));
    }

    @Test
    void theListenerCanStopTheSpeech() {
        contentType = "text/event-stream";
        reply = event(b64(bytes(1, 2))) + event(b64(bytes(3, 4))) + event(b64(bytes(5, 6)));
        List<byte[]> received = new ArrayList<>();

        gemini(true).speak("Hi", "Kore", "", pcm -> {
            received.add(pcm);
            return false;
        });

        assertEquals(1, received.size());
    }

    @Test
    void aStreamWithoutAudioIsAnError() {
        contentType = "text/event-stream";
        reply = "data: {\"event_type\":\"step.start\"}\n\n";
        assertTrue(assertThrows(SpeechException.class, () -> gemini(true).speak("Hi", "Kore", "", pcm -> true))
                .getMessage().contains("no audio"));
    }

    @Test
    void aRefusedSpeechRequestIsReported() {
        status = 400;
        reply = "{\"error\":{\"message\":\"bad voice\"}}";
        assertTrue(assertThrows(SpeechException.class, () -> gemini(true).speak("Hi", "Nobody", "", pcm -> true))
                .getMessage().contains("400"));
    }

    @Test
    void theWholeFileFallbackAsksForRawAudioAndSlicesIt() {
        byte[] audio = new byte[25_000];
        reply = "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\"" + Base64.getEncoder().encodeToString(audio) + "\"}]}]}";
        List<Integer> sizes = new ArrayList<>();

        gemini(false).speak("Hello", "Kore", "", pcm -> {
            sizes.add(pcm.length);
            return true;
        });

        assertEquals(25_000, sizes.stream().mapToInt(Integer::intValue).sum());
        assertTrue(sizes.size() >= 3, "the audio should arrive in slices, not one block");
        assertEquals("audio/l16", bodies.get(0).path("response_format").path("mime_type").asString());
        assertFalse(bodies.get(0).has("stream"));
    }

    // ---- helpers ----

    @Test
    void pcmHelpers() {
        byte[] wav = Pcm.wav(bytes(1, 0, 2, 0), 16_000);
        assertEquals(48, wav.length);
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(16_000, java.nio.ByteBuffer.wrap(wav, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());

        assertEquals(0.0, Pcm.peak(new byte[100]));
        assertEquals(0.5, Pcm.peak(bytes(0x00, 0x40)), 0.001);
        assertEquals(1000, Pcm.millis(32_000, 16_000));
    }
}
