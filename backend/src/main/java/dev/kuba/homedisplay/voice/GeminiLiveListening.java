package dev.kuba.homedisplay.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One sentence heard while it is spoken, over Gemini's Live API (a WebSocket). The connection is opened when the
 * sentence starts and the audio goes out as it arrives, so when the speaker stops only the last moments are left to
 * transcribe.
 *
 * <p>The service wants audio at the pace it is spoken: a recording sent in one burst comes back garbled. So audio is
 * sent as it arrives, and a backlog (audio that came in while the connection was still being set up) is caught up at
 * a few times the speed of speech, which was found to be safe.
 *
 * <p>Anything unexpected makes {@link #finish} return null, and the caller transcribes the recording the ordinary way.
 * The key goes in a header and is never logged; errors are scrubbed of it.
 */
final class GeminiLiveListening implements SpeechToText.Live, WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveListening.class);
    private static final byte[] END = new byte[0];
    /** How much faster than speech a backlog is sent. */
    private static final int CATCH_UP = 4;
    private static final int BYTES_PER_MS = SpeechToText.SAMPLE_RATE * 2 / 1000;
    private static final Duration CONNECT_WAIT = Duration.ofSeconds(5);

    private final JsonMapper json;
    private final String key;
    private final Duration finishWait;
    private final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final StringBuilder message = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private final StringBuilder transcript = new StringBuilder();
    private final Thread sender;
    private final String setup;
    private volatile WebSocket socket;
    /** Set once the end of the audio has gone out: only then does a "generation complete" mean the sentence is done. */
    private volatile boolean endSent;
    private volatile boolean closed;

    /**
     * @param lifetime   the longest a sentence may keep the connection open
     * @param finishWait how long to wait for the text once the audio has ended
     */
    GeminiLiveListening(HttpClient http, URI uri, String key, String model, String mode, List<String> languages,
            List<String> vocabulary, Duration lifetime, Duration finishWait, JsonMapper json) {
        this.json = json;
        this.key = key;
        this.finishWait = finishWait;

        ObjectNode setupMessage = json.createObjectNode();
        ObjectNode setup = setupMessage.putObject("setup");
        setup.put("model", "models/" + model);
        setup.putObject("generationConfig").putArray("responseModalities").add("TEXT");
        ObjectNode transcription = setup.putObject("inputAudioTranscription");
        var codes = transcription.putArray("languageCodes"); // empty: the service detects the language
        languages.forEach(codes::add);
        transcription.put("mode", mode);
        if (!vocabulary.isEmpty()) {
            var terms = transcription.putArray("customVocabulary");
            vocabulary.stream().limit(100).forEach(terms::add);
        }
        this.setup = setupMessage.toString();

        this.sender = Thread.ofVirtual().name("live-listening").unstarted(() -> send(lifetime));
        http.newWebSocketBuilder().connectTimeout(CONNECT_WAIT).header("x-goog-api-key", key).buildAsync(uri, this)
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        fail("could not connect: " + describe(error));
                    }
                });
        sender.start();
    }

    // ---- what the caller does ----

    @Override
    public void accept(byte[] pcm) {
        if (!closed && pcm.length > 0) {
            audio.add(pcm);
        }
    }

    @Override
    public String finish() {
        audio.add(END);
        try {
            return result.get(finishWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("Live transcription did not answer within {} ms", finishWait.toMillis());
            return null;
        } catch (ExecutionException e) {
            log.info("Live transcription failed: {}", describe(e.getCause()));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            close();
        }
    }

    @Override
    public void cancel() {
        close();
    }

    private void close() {
        closed = true;
        result.cancel(false);
        sender.interrupt();
        WebSocket open = socket;
        if (open != null) {
            open.abort();
        }
    }

    private void fail(String why) {
        if (result.completeExceptionally(new SpeechException(why))) {
            ready.completeExceptionally(new SpeechException(why));
        }
    }

    /** An error as text, without the key in case it was repeated in it. */
    private String describe(Throwable error) {
        String text = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return key.isEmpty() ? text : text.replace(key, "***");
    }

    // ---- sending ----

    private void send(Duration lifetime) {
        try {
            ready.get(CONNECT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            long deadline = System.nanoTime() + lifetime.toNanos();
            while (true) {
                byte[] chunk = audio.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    fail("the sentence went on too long");
                    return;
                }
                if (chunk == END) {
                    socket.sendText("{\"realtimeInput\":{\"audioStreamEnd\":true}}", true).get(CONNECT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    endSent = true;
                    return;
                }
                ObjectNode input = json.createObjectNode();
                input.putObject("realtimeInput").putObject("audio").put("data", Base64.getEncoder().encodeToString(chunk))
                        .put("mimeType", "audio/pcm;rate=" + SpeechToText.SAMPLE_RATE);
                socket.sendText(input.toString(), true).get(CONNECT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                if (!audio.isEmpty()) {
                    Thread.sleep(Math.max(1, chunk.length / BYTES_PER_MS / CATCH_UP));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            if (!closed) {
                fail("could not send audio: " + describe(e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e));
            }
        }
    }

    // ---- receiving ----

    @Override
    public void onOpen(WebSocket webSocket) {
        this.socket = webSocket;
        webSocket.sendText(setup, true).whenComplete((w, error) -> {
            if (error != null) {
                fail("could not start: " + describe(error));
            }
        });
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        message.append(data);
        if (last) {
            String whole = message.toString();
            message.setLength(0);
            handle(whole);
        }
        webSocket.request(1);
        return null;
    }

    /** The service sends its JSON messages as binary frames as often as text ones. */
    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        binary.writeBytes(bytes);
        if (last) {
            String whole = binary.toString(StandardCharsets.UTF_8);
            binary.reset();
            handle(whole);
        }
        webSocket.request(1);
        return null;
    }

    private void handle(String text) {
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (JacksonException e) {
            return;
        }
        if (node.has("setupComplete")) {
            ready.complete(null);
            return;
        }
        if (node.has("error")) {
            fail("the service refused: " + node.path("error").path("message").asString("unknown reason"));
            return;
        }
        JsonNode content = node.path("serverContent");
        if (content.path("inputTranscription").hasNonNull("text")) {
            String piece = content.path("inputTranscription").path("text").asString();
            if (!transcript.isEmpty() && !Character.isWhitespace(transcript.charAt(transcript.length() - 1))
                    && !piece.isEmpty() && !Character.isWhitespace(piece.charAt(0))) {
                transcript.append(' ');
            }
            transcript.append(piece);
        }
        if (endSent && (content.path("generationComplete").asBoolean(false) || content.path("turnComplete").asBoolean(false))) {
            result.complete(transcript.toString().trim());
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!result.isDone()) {
            fail("the connection closed (" + statusCode + ")");
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        fail("the connection broke: " + describe(error));
    }
}
