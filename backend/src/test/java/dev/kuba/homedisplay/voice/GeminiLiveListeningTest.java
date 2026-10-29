package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.kuba.homedisplay.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Hearing a sentence while it is spoken, against a fake of Gemini's Live API that speaks real WebSocket. */
class GeminiLiveListeningTest {

    private static final String SETUP_DONE = "{\"setupComplete\":{}}";
    private static final String ENDED = "{\"serverContent\":{\"generationComplete\":true}}";

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private FakeLiveServer server;

    @BeforeEach
    void start() throws IOException {
        server = new FakeLiveServer();
        // the usual service: ready at once, and once the audio has ended it says what it heard
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                server.send(SETUP_DONE);
            } else if (message.contains("audioStreamEnd")) {
                server.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\"Turn on the lamp.\"}}}");
                server.send(ENDED);
            }
        };
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    private GeminiLiveListening listening(Duration finishWait) {
        return new GeminiLiveListening(http, URI.create("ws://127.0.0.1:" + server.port() + "/live"), "secret-key",
                "gemini-3.5-transcribe-live", "SMART", List.of("pl-PL", "en-US"), List.of("Desk lamp", "Movie"), Duration.ofSeconds(15),
                finishWait, json);
    }

    private GeminiLiveListening listening() {
        return listening(Duration.ofSeconds(3));
    }

    private static byte[] audio(int bytes, int fill) {
        byte[] audio = new byte[bytes];
        java.util.Arrays.fill(audio, (byte) fill);
        return audio;
    }

    // ---- what it says to the service ----

    @Test
    void itSetsUpTheModelLanguagesAndNamesThenStreamsTheAudioAndEndsIt() throws Exception {
        GeminiLiveListening live = listening();
        live.accept(audio(3200, 1));
        live.accept(audio(3200, 2));

        assertEquals("Turn on the lamp.", live.finish());

        server.awaitReceived(4);
        assertEquals("secret-key", server.keyHeader);
        JsonNode setup = json.readTree(server.received.get(0)).path("setup");
        assertEquals("models/gemini-3.5-transcribe-live", setup.path("model").asString());
        assertEquals("TEXT", setup.path("generationConfig").path("responseModalities").get(0).asString());
        JsonNode transcription = setup.path("inputAudioTranscription");
        assertEquals(List.of("pl-PL", "en-US"), List.of(transcription.path("languageCodes").get(0).asString(), transcription.path("languageCodes").get(1).asString()));
        assertEquals("Desk lamp", transcription.path("customVocabulary").get(0).asString());
        assertEquals("SMART", transcription.path("mode").asString());

        JsonNode first = json.readTree(server.received.get(1)).path("realtimeInput").path("audio");
        assertEquals("audio/pcm;rate=16000", first.path("mimeType").asString());
        assertEquals(List.of((byte) 1, (byte) 1), List.of(Base64.getDecoder().decode(first.path("data").asString())[0], Base64.getDecoder().decode(first.path("data").asString())[3199]));
        assertEquals((byte) 2, Base64.getDecoder().decode(json.readTree(server.received.get(2)).path("realtimeInput").path("audio").path("data").asString())[0]);
        assertTrue(json.readTree(server.received.get(3)).path("realtimeInput").path("audioStreamEnd").asBoolean(), server.received.get(3));
    }

    @Test
    void audioThatArrivesBeforeTheConnectionIsReadyIsSentInOrderOnceItIs() throws Exception {
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                Thread.startVirtualThread(() -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                    server.send(SETUP_DONE);
                });
            } else if (message.contains("audioStreamEnd")) {
                server.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\"late but whole\"}}}");
                server.send(ENDED);
            }
        };
        GeminiLiveListening live = listening();
        for (int i = 1; i <= 8; i++) {
            live.accept(audio(3200, i));
        }

        assertEquals("late but whole", live.finish());

        server.awaitReceived(10);
        for (int i = 1; i <= 8; i++) {
            byte[] sent = Base64.getDecoder().decode(json.readTree(server.received.get(i)).path("realtimeInput").path("audio").path("data").asString());
            assertEquals((byte) i, sent[0], "chunk " + i);
        }
    }

    // ---- what it makes of the answers ----

    @Test
    void piecesOfTheTranscriptAreJoinedAndAReplySplitOverFramesIsUnderstood() throws Exception {
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                server.send(SETUP_DONE, true);
            } else if (message.contains("audioStreamEnd")) {
                server.send("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"Tu\"}}}");
                server.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\"Turn on\"}}}", true);
                server.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\"the lamp.\"}}}");
                server.send(ENDED);
            }
        };
        GeminiLiveListening live = listening();
        live.accept(audio(3200, 1));

        assertEquals("Turn on the lamp.", live.finish());
    }

    @Test
    void aSentenceThatEndsInThePausesOfTheSpeakerIsNotCutShort() throws Exception {
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                server.send(SETUP_DONE);
            } else if (message.contains("audioStreamEnd")) {
                server.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\" and the main one off.\"}}}");
                server.send(ENDED);
            } else {
                // the service finishes a part at every pause, long before the audio has ended
                server.send("{\"serverContent\":{\"inputTranscription\":{\"text\":\"Turn on the lamp\"}}}");
                server.send(ENDED);
            }
        };
        GeminiLiveListening live = listening();
        live.accept(audio(3200, 1));
        server.awaitReceived(2);
        TimeUnit.MILLISECONDS.sleep(200);

        assertEquals("Turn on the lamp and the main one off.", live.finish());
    }

    @Test
    void nothingHeardIsAnEmptyTextNotAFailure() throws Exception {
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                server.send(SETUP_DONE);
            } else if (message.contains("audioStreamEnd")) {
                server.send(ENDED);
            }
        };
        GeminiLiveListening live = listening();
        live.accept(audio(3200, 0));

        assertEquals("", live.finish());
    }

    // ---- when it goes wrong, the caller is told so it can use the recording ----

    @Test
    void aServiceThatNeverAnswersIsGivenUpOnAfterTheWait() throws Exception {
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                server.send(SETUP_DONE);
            }
        };
        GeminiLiveListening live = listening(Duration.ofMillis(400));
        live.accept(audio(3200, 1));

        long start = System.nanoTime();
        assertNull(live.finish());
        long took = (System.nanoTime() - start) / 1_000_000;
        assertTrue(took >= 350 && took < 2500, "took " + took + " ms");
    }

    @Test
    void aServiceThatHangsUpOrRefusesIsNoticedAtOnce() throws Exception {
        server.script = message -> {
            if (message.contains("\"setup\"")) {
                try {
                    server.drop();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        GeminiLiveListening hangsUp = listening(Duration.ofSeconds(3));
        hangsUp.accept(audio(3200, 1));
        long start = System.nanoTime();
        assertNull(hangsUp.finish());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);

        server.script = message -> {
            if (message.contains("\"setup\"")) {
                server.send("{\"error\":{\"message\":\"API key not valid\"}}");
            }
        };
        GeminiLiveListening refused = listening(Duration.ofSeconds(3));
        refused.accept(audio(3200, 1));
        start = System.nanoTime();
        assertNull(refused.finish());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
    }

    @Test
    void anAddressNobodyListensOnIsNoticedAtOnce() {
        GeminiLiveListening live = new GeminiLiveListening(http, URI.create("ws://127.0.0.1:1/live"), "secret-key", "m", "SMART",
                List.of(), List.of(), Duration.ofSeconds(15), Duration.ofSeconds(3), json);
        live.accept(audio(3200, 1));
        long start = System.nanoTime();
        assertNull(live.finish());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
    }

    @Test
    void cancellingClosesTheConnection() throws Exception {
        GeminiLiveListening live = listening();
        live.accept(audio(3200, 1));
        server.awaitReceived(2);

        live.cancel();

        assertTrue(server.clientClosed.await(3, TimeUnit.SECONDS), "the connection was left open");
    }

    // ---- through GeminiSpeech ----

    private GeminiSpeech gemini(boolean liveTranscription) {
        return new GeminiSpeech(new AppProperties.Speech("secret-key", "gemini-3.5-transcribe", "SMART", "gemini-3.8-flash-tts", true,
                "gemini-3.5-transcribe-live", liveTranscription, false, 15, false, "http://127.0.0.1:" + server.port()), json);
    }

    @Test
    void geminiSpeechOpensTheLiveConnectionOnItsOwnAddressAndCanBeSwitchedOff() throws Exception {
        SpeechToText.Live live = gemini(true).listenLive(List.of("pl-PL"), List.of());
        assertNotNull(live);
        live.accept(audio(3200, 1));
        assertEquals("Turn on the lamp.", live.finish());
        assertEquals("secret-key", server.keyHeader);

        assertNull(gemini(false).listenLive(List.of("pl-PL"), List.of()));
    }
}
