package dev.kuba.homedisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import dev.kuba.homedisplay.voice.AgentModel.Step;
import dev.kuba.homedisplay.voice.AgentModel.ToolCall;
import dev.kuba.homedisplay.voice.SpeechException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The microphone session end to end over a real WebSocket, with scripted listening, thinking and speaking. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "app.speech.max-utterance-seconds=1", "app.voice.requests-per-minute=3" })
@ActiveProfiles("test")
@Import(VoiceSessionTest.Config.class)
class VoiceSessionTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedAgentModel sessionModel() {
            return new ScriptedAgentModel();
        }

        @Bean
        @Primary
        ScriptedSpeech sessionSpeech() {
            return new ScriptedSpeech();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ScriptedAgentModel model;
    @Autowired
    ScriptedSpeech speech;
    @Autowired
    dev.kuba.homedisplay.voice.SpokenPhrases phrases;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Client> opened = new ArrayList<>();

    @BeforeEach
    void reset() {
        model.reset();
        speech.reset();
        phrases.clear();
    }

    @AfterEach
    void closeSockets() {
        for (Client client : opened) {
            if (!client.socket.isOutputClosed()) {
                client.socket.sendClose(WebSocket.NORMAL_CLOSURE, "test over");
            }
        }
        opened.clear();
    }

    // ---- helpers ----

    /** Collects everything the server sends in order: text events as their type (state events with their state), audio as "bin". */
    private final class Client implements WebSocket.Listener {
        final BlockingQueue<String> labels = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        int audioBytes;
        private String lastLabel;
        WebSocket socket;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                JsonNode event = json.readTree(partial.toString());
                partial.setLength(0);
                String type = event.path("type").asString();
                label("state".equals(type) ? "state:" + event.path("state").asString() : type);
                events.add(event);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            audioBytes += data.remaining();
            if (!"bin".equals(lastLabel)) {
                label("bin");
            }
            webSocket.request(1);
            return null;
        }

        private synchronized void label(String label) {
            lastLabel = label;
            labels.add(label);
        }

        String next() throws InterruptedException {
            String label = labels.poll(5, TimeUnit.SECONDS);
            assertNotNull(label, "the server said nothing within 5 seconds");
            return label;
        }

        /** Labels up to and including the one given. */
        List<String> until(String label) throws InterruptedException {
            List<String> seen = new ArrayList<>();
            String current;
            do {
                current = next();
                seen.add(current);
            } while (!current.equals(label));
            return seen;
        }

        JsonNode event(String type) throws InterruptedException {
            while (true) {
                JsonNode event = events.poll(5, TimeUnit.SECONDS);
                assertNotNull(event, "no " + type + " event within 5 seconds");
                if (event.path("type").asString().equals(type)) {
                    return event;
                }
            }
        }

        void say(String message) {
            socket.sendText(message, true).join();
        }

        /** A whole utterance: start, frames of 3200 bytes, end. */
        void speak(byte[] audio, boolean speakBack) {
            say("{\"type\":\"utterance_start\",\"speak\":" + speakBack + "}");
            for (int at = 0; at < audio.length; at += 3200) {
                socket.sendBinary(ByteBuffer.wrap(audio, at, Math.min(3200, audio.length - at)), true).join();
            }
            say("{\"type\":\"utterance_end\"}");
        }
    }

    private static byte[] loud(int bytes) {
        byte[] audio = new byte[bytes];
        for (int i = 0; i + 1 < bytes; i += 2) {
            audio[i + 1] = 0x40; // 0x4000 little-endian: half of full scale
        }
        return audio;
    }

    private String loginCookie() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"testpass\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private String displayCookie(String userCookie) throws Exception {
        String base = "http://localhost:" + port;
        String start = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/pair/start")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String deviceCode = start.replaceAll(".*\"deviceCode\":\"([^\"]+)\".*", "$1");
        String userCode = start.replaceAll(".*\"userCode\":\"([^\"]+)\".*", "$1");
        http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/pair/approve")).header("Cookie", userCookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userCode\":\"" + userCode + "\",\"label\":\"Test door\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> poll = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/pair/poll?deviceCode=" + deviceCode)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String cookie = poll.headers().firstValue("Set-Cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private Client connect(String cookie, String... extraHeaders) throws Exception {
        Client client = new Client();
        WebSocket.Builder builder = http.newWebSocketBuilder();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            builder.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        client.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/api/voice/session"), client).get(5, TimeUnit.SECONDS);
        opened.add(client);
        return client;
    }

    /** The HTTP status the server refused the WebSocket upgrade with. */
    private static int refusedWith(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.WebSocketHandshakeException refused) {
                return refused.getResponse().statusCode();
            }
        }
        throw new AssertionError("not a refused handshake: " + failure);
    }

    private String refOf(String name) {
        Matcher m = Pattern.compile("\\b(l\\d+)\\s+\"" + Pattern.quote(name) + "\"").matcher(model.system);
        assertTrue(m.find(), "no reference for " + name);
        return m.group(1);
    }

    // ---- the happy path ----

    @Test
    void aSpokenSentenceIsHeardAnsweredAndSpokenBack() throws Exception {
        Client client = connect(loginCookie());
        JsonNode ready = client.event("ready");
        assertEquals(24000, ready.path("sampleRate").asInt());
        assertEquals(1, ready.path("maxUtteranceSeconds").asInt());
        assertTrue(ready.path("listening").asBoolean() && ready.path("speaking").asBoolean());
        client.labels.clear();

        model.onUser = text -> new Step("", List.of(new ToolCall("c1", "set_lights", json.readTree(
                "{\"changes\":[{\"light\":\"" + refOf("Desk lamp (fake)") + "\",\"on\":true}]}"))), false, false);
        model.onResults = results -> Step.text("The desk lamp is on and the room is bright.");
        speech.transcript = "turn on the lamp";

        client.speak(loud(16_000), true);

        assertEquals(List.of("state:transcribing", "transcript", "state:thinking", "reply", "state:speaking", "audio_start", "bin",
                "audio_end", "state:idle"), client.until("state:idle"));
        assertEquals(1, speech.listens.get());
        assertEquals(16_000, speech.heardBytes.get(0));
        assertEquals(List.of("pl-PL", "en-US"), speech.heardLanguages.get(0));
        assertTrue(speech.heardVocabulary.get(0).contains("Desk lamp (fake)"), speech.heardVocabulary.get(0).toString());
        // everyday words such as the channel names would make the listener rewrite "the background light" as "Background"
        assertFalse(speech.heardVocabulary.get(0).contains("Background") || speech.heardVocabulary.get(0).contains("Main"),
                speech.heardVocabulary.get(0).toString());
        assertEquals("The desk lamp is on and the room is bright.", speech.spoken.get(0).text());
        assertEquals("Kore", speech.spoken.get(0).voice());
        assertEquals(ScriptedSpeech.PIECES * 1000, client.audioBytes);
        assertEquals(1, model.userTexts.size());
        assertTrue(model.userTexts.get(0).startsWith("turn on the lamp"));
    }

    @Test
    void theTranscriptAndReplyCarryWhatWasDone() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        model.onUser = text -> new Step("", List.of(new ToolCall("c1", "set_lights", json.readTree(
                "{\"changes\":[{\"light\":\"" + refOf("Desk lamp (fake)") + "\",\"on\":true,\"brightness\":30}]}"))), false, false);
        model.onResults = results -> Step.text("Lamp at thirty percent.");
        speech.transcript = "lamp to thirty";

        client.speak(loud(16_000), false);

        assertEquals("lamp to thirty", client.event("transcript").path("text").asString());
        JsonNode reply = client.event("reply");
        assertEquals("Lamp at thirty percent.", reply.path("reply").asString());
        assertEquals("Desk lamp (fake): on, 30%", reply.path("actions").get(0).asString());
        assertNotNull(reply.path("conversationId").asString());
    }

    @Test
    void theNextSentenceContinuesTheSameConversation() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");

        client.speak(loud(16_000), false);
        client.until("state:idle");
        client.speak(loud(16_000), false);
        client.until("state:idle");

        assertEquals(1, model.started.get());
        assertEquals(2, model.userTexts.size());
    }

    @Test
    void whenTheListenerDoesNotWantSpeechNothingIsSpoken() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        client.labels.clear();

        client.speak(loud(16_000), false);

        assertEquals(List.of("state:transcribing", "transcript", "state:thinking", "reply", "state:idle"), client.until("state:idle"));
        assertTrue(speech.spoken.isEmpty());
    }

    // ---- sounds that are not speech ----

    @Test
    void silenceAndTinyClipsNeverReachTheListeningService() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        client.labels.clear();

        client.speak(new byte[16_000], true); // quiet
        assertEquals(List.of("no_speech", "state:idle"), client.until("state:idle"));
        client.speak(loud(1_000), true); // too short to be a sentence
        assertEquals(List.of("no_speech", "state:idle"), client.until("state:idle"));

        assertEquals(0, speech.listens.get());
    }

    @Test
    void aBlankTranscriptIsNoSpeech() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        client.labels.clear();
        speech.transcript = "  ";

        client.speak(loud(16_000), true);

        assertEquals(List.of("state:transcribing", "no_speech", "state:idle"), client.until("state:idle"));
        assertTrue(model.userTexts.isEmpty());
    }

    @Test
    void aTooLongRecordingIsRefused() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");

        client.speak(loud(40_000), true); // the limit in this test is one second: 32,000 bytes

        assertTrue(client.event("error").path("message").asString().contains("too long"));
        assertEquals(0, speech.listens.get());
    }

    // ---- interrupting ----

    @Test
    void speakingOverTheAnswerStopsIt() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        speech.gate = new CountDownLatch(1);

        client.speak(loud(16_000), true);
        client.until("audio_start");
        assertEquals("bin", client.next());
        client.say("{\"type\":\"interrupt\"}");
        assertEquals("interrupted", client.event("interrupted").path("type").asString());
        speech.gate.countDown(); // only now may the speaker carry on: the server has certainly seen the interrupt
        Thread.sleep(300);
        assertFalse(client.labels.contains("audio_end"), "an interrupted answer must not be marked finished");
        assertTrue(speech.stoppedBySink, "the speech service should have been told to stop");
        assertTrue(client.audioBytes < ScriptedSpeech.PIECES * 1000);
    }

    // ---- trouble ----

    @Test
    void ifListeningFailsThePageIsToldAndCanTryAgain() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        speech.listenFailure = new SpeechException("The speech service is busy, try again in a moment");

        client.speak(loud(16_000), true);
        assertEquals("The speech service is busy, try again in a moment", client.event("error").path("message").asString());
        client.until("state:idle");

        speech.listenFailure = null;
        client.speak(loud(16_000), true);
        assertEquals("reply", client.until("reply").getLast());
    }

    @Test
    void ifSpeakingFailsTheTextReplyStillArrives() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        speech.speakFailure = new SpeechException("The speech service could not be reached");

        client.speak(loud(16_000), true);

        client.event("reply");
        assertEquals("The speech service could not be reached", client.event("error").path("message").asString());
    }

    @Test
    void tooManySentencesInAMinuteStopBeforeThePaidListeningStep() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        for (int i = 0; i < 3; i++) {
            client.speak(loud(16_000), false);
            client.until("state:idle");
        }

        client.speak(loud(16_000), false);

        assertTrue(client.event("error").path("message").asString().contains("Slow down"));
        assertEquals(3, speech.listens.get());
    }

    // ---- who may connect ----

    @Test
    void aDoorScreenMayUseTheMicrophoneToo() throws Exception {
        Client client = connect(displayCookie(loginCookie()));
        assertEquals("ready", client.next());
    }

    @Test
    void anonymousVisitorsAndOtherSitesAreRefused() throws Exception {
        CompletionException anonymous = assertThrows(CompletionException.class, () -> http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/api/voice/session"), new Client()).join());
        assertEquals(401, refusedWith(anonymous));

        String cookie = loginCookie();
        Exception otherSite = assertThrows(Exception.class, () -> connect(cookie, "Origin", "https://evil.example"));
        assertEquals(403, refusedWith(otherSite));
    }

    // ---- hearing the sentence while it is spoken ----

    @Test
    void aSentenceHeardWhileSpokenIsNotTranscribedAgain() throws Exception {
        speech.live = true;
        speech.liveTranscript = "turn on the lamp";
        speech.transcript = "the slow way would say something else";
        Client client = connect(loginCookie());
        client.event("ready");

        client.speak(loud(16_000), false);

        assertEquals("turn on the lamp", client.event("transcript").path("text").asString());
        client.until("state:idle");
        assertEquals(1, speech.liveStarts.get());
        assertEquals(16_000, speech.liveBytes.get());
        assertEquals(1, speech.liveFinishes.get());
        assertEquals(0, speech.listens.get());
        assertEquals(List.of("pl-PL", "en-US"), speech.liveLanguages.get(0));
    }

    @Test
    void whenHearingWhileSpokenFailsTheRecordingIsTranscribedInstead() throws Exception {
        speech.live = true;
        speech.liveTranscript = null; // the connection broke or took too long
        speech.transcript = "turn on the lamp";
        Client client = connect(loginCookie());
        client.event("ready");

        client.speak(loud(16_000), false);

        assertEquals("turn on the lamp", client.event("transcript").path("text").asString());
        client.until("state:idle");
        assertEquals(1, speech.liveFinishes.get());
        assertEquals(1, speech.listens.get());
        assertEquals(16_000, speech.heardBytes.get(0));
    }

    @Test
    void whenTheLiveListeningIsSlowTheRecordingIsTranscribedAsWellAndTheFirstTextIsUsed() throws Exception {
        speech.live = true;
        speech.liveDelayMillis = 6000; // far slower than the patience
        speech.liveTranscript = "the slow live text";
        speech.transcript = "turn on the lamp";
        Client client = connect(loginCookie());
        client.event("ready");

        long start = System.nanoTime();
        client.speak(loud(16_000), false);

        assertEquals("turn on the lamp", client.event("transcript").path("text").asString());
        long took = (System.nanoTime() - start) / 1_000_000;
        assertTrue(took < 4000, "waited for the slow live listening: " + took + " ms");
        assertEquals(1, speech.listens.get());
        client.until("state:idle");
        awaitCount(speech.liveCancels, 1); // the live one is given up on
    }

    @Test
    void nothingHeardWhileSpokenIsNothingNotAReasonToTryAgain() throws Exception {
        speech.live = true;
        speech.liveTranscript = "";
        Client client = connect(loginCookie());
        client.event("ready");

        client.speak(loud(16_000), false);

        client.event("no_speech");
        assertEquals(0, speech.listens.get());
    }

    @Test
    void aSentenceThatIsCancelledOrTooQuietOrTooShortStopsTheLiveListening() throws Exception {
        speech.live = true;
        Client client = connect(loginCookie());
        client.event("ready");

        client.say("{\"type\":\"utterance_start\"}");
        client.socket.sendBinary(ByteBuffer.wrap(loud(3200)), true).join();
        client.say("{\"type\":\"utterance_cancel\"}");
        awaitCount(speech.liveCancels, 1);

        client.speak(new byte[16_000], false); // silence
        client.event("no_speech");
        awaitCount(speech.liveCancels, 2);

        client.speak(loud(1000), false); // under 300 ms
        client.event("no_speech");
        awaitCount(speech.liveCancels, 3);
        assertEquals(0, speech.liveFinishes.get());
        assertEquals(0, speech.listens.get());
    }

    @Test
    void startingAgainOrHangingUpStopsTheSentenceThatWasOpen() throws Exception {
        speech.live = true;
        Client client = connect(loginCookie());
        client.event("ready");

        client.say("{\"type\":\"utterance_start\"}");
        client.say("{\"type\":\"utterance_start\"}"); // the first one is abandoned
        awaitCount(speech.liveCancels, 1);
        assertEquals(2, speech.liveStarts.get());

        client.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        awaitCount(speech.liveCancels, 2);
    }

    private static void awaitCount(java.util.concurrent.atomic.AtomicInteger counter, int expected) throws InterruptedException {
        for (int i = 0; i < 100 && counter.get() < expected; i++) {
            Thread.sleep(50);
        }
        assertEquals(expected, counter.get());
    }

    // ---- the wake word is answered ----

    /** Has the assistant say "Yes?" once in a real turn, which is how a phrase comes to be remembered. */
    private void rememberYes(Client client) throws Exception {
        model.onUser = text -> Step.text("Yes?");
        client.speak(loud(16_000), true);
        client.until("state:idle");
        client.labels.clear();
        speech.spoken.clear();
    }

    private void saveSettings(String json) {
        jdbc.sql("""
                insert into setting (key, value, updated_at) values ('voice.settings', ?, '2026-10-29T00:00:00Z')
                on conflict(key) do update set value = excluded.value""").param(json).update();
    }

    @Test
    void theWakeWordIsAnsweredFromMemoryAtOnceWithTheReplyThatWasSet() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        rememberYes(client);

        client.say("{\"type\":\"wake\"}");

        assertEquals(List.of("ack", "bin", "ack_end"), client.until("ack_end"));
        assertEquals(24000, client.event("ack").path("sampleRate").asInt());
        assertTrue(client.audioBytes >= ScriptedSpeech.PIECES * 1000);
        assertTrue(speech.spoken.isEmpty(), "it was not made again");
    }

    @Test
    void aReplyThatIsNotMadeYetIsNotWaitedForAndNothingIsSaid() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        client.labels.clear();

        client.say("{\"type\":\"wake\"}");
        Thread.sleep(600);

        assertTrue(client.labels.isEmpty(), client.labels.toString());
        assertTrue(speech.spoken.isEmpty(), "it would have arrived after the person started to speak");
    }

    @Test
    void noReplyIsSaidWhenItIsEmptyOrWhenAnswersAreNotSpoken() throws Exception {
        Client client = connect(loginCookie());
        client.event("ready");
        rememberYes(client);
        try {
            saveSettings("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true,\"wakeSensitivity\":\"normal\",\"wakeReply\":\"\"}");
            client.say("{\"type\":\"wake\"}");
            Thread.sleep(500);
            assertTrue(client.labels.isEmpty(), client.labels.toString());

            saveSettings("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":false,\"wakeSensitivity\":\"normal\",\"wakeReply\":\"Yes?\"}");
            client.say("{\"type\":\"wake\"}");
            Thread.sleep(500);
            assertTrue(client.labels.isEmpty(), client.labels.toString());
        } finally {
            jdbc.sql("delete from setting where key = 'voice.settings'").update();
        }
    }
}
