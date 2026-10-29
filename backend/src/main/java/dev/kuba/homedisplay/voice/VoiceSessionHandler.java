package dev.kuba.homedisplay.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import dev.kuba.homedisplay.config.AppProperties;
import dev.kuba.homedisplay.security.CredentialPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One microphone conversation over a WebSocket. The page records, detects when a sentence ends, and sends the
 * sentence; this handler listens, thinks and speaks, and tells the page what is going on.
 *
 * <pre>
 * page   -> {"type":"utterance_start","speak":true,"conversationId":"..."}  then binary PCM (16 kHz mono) frames
 * page   -> {"type":"utterance_end"}  (or utterance_cancel)
 * server -> {"type":"state","state":"transcribing" | "thinking" | "speaking" | "idle"}
 * server -> {"type":"transcript","text":"..."}
 * server -> {"type":"reply","reply":"...","actions":[],"failed":[],"conversationId":"..."}
 * server -> {"type":"audio_start","sampleRate":24000}  then binary PCM (24 kHz mono) frames, then {"type":"audio_end"}
 * page   -> {"type":"interrupt"}  stops a running turn (the listener spoke over the answer)
 * page   -> {"type":"wake"}  the wake word was heard on the door screen: say the wake reply ("Yes?") if there is one
 * server -> {"type":"ack","sampleRate":24000}  then binary PCM frames, then {"type":"ack_end"}  (instant, or not at all)
 * page   -> {"type":"ping"}  every so often while idle, to keep the connection alive; no answer
 * server -> {"type":"no_speech"} | {"type":"error","message":"..."} | {"type":"interrupted"}
 * </pre>
 *
 * One turn runs at a time per connection; starting a new sentence cancels the old turn.
 */
@Component
class VoiceSessionHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceSessionHandler.class);
    static final String PRINCIPAL = "principal";
    /** Less than this much audio cannot be a sentence (300 ms), so the listening service is not paid to hear it. */
    private static final int MIN_BYTES = SpeechToText.SAMPLE_RATE * 2 * 3 / 10;
    /** A recording whose loudest sample is quieter than this is silence or room hum. */
    private static final double SILENCE_PEAK = 0.01;
    private static final int OUT_FRAME = 9600;
    /**
     * The live listening usually has the text 0.3 s after the speech ends, but now and then it takes several seconds. After this
     * long the recording is sent as well, and whichever gives the text first is used.
     */
    static final long LIVE_PATIENCE_MS = 1500;
    private static final Executor VIRTUAL = command -> Thread.ofVirtual().name("live-wait").start(command);

    private final VoiceService voice;
    private final SpeechToText listener;
    private final TextToSpeech speaker;
    private final VoiceSettingsService settings;
    private final SpokenPhrases phrases;
    private final JsonMapper json;
    private final int maxBytes;
    private final boolean compare;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    VoiceSessionHandler(VoiceService voice, SpeechToText listener, TextToSpeech speaker, VoiceSettingsService settings,
            SpokenPhrases phrases, JsonMapper json, AppProperties props) {
        this.voice = voice;
        this.listener = listener;
        this.speaker = speaker;
        this.settings = settings;
        this.phrases = phrases;
        this.json = json;
        this.maxBytes = props.speech().maxUtteranceSeconds() * SpeechToText.SAMPLE_RATE * 2;
        this.compare = props.speech().compareTranscripts();
    }

    /** What belongs to one open connection. */
    private static final class Connection {
        final WebSocketSession out;
        final CredentialPrincipal who;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        boolean receiving;
        boolean tooLong;
        boolean speak = true;
        String conversationId;
        /** The sentence being heard as it is spoken, if the listening service can do that. */
        SpeechToText.Live live;
        AtomicBoolean cancelled = new AtomicBoolean();

        Connection(WebSocketSession out, CredentialPrincipal who) {
            this.out = out;
            this.who = who;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        CredentialPrincipal who = (CredentialPrincipal) session.getAttributes().get(PRINCIPAL);
        if (who == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Connection connection = new Connection(new ConcurrentWebSocketSessionDecorator(session, 10_000, 4 * 1024 * 1024), who);
        connections.put(session.getId(), connection);
        send(connection, json.createObjectNode().put("type", "ready").put("sampleRate", TextToSpeech.SAMPLE_RATE)
                .put("maxUtteranceSeconds", maxBytes / (SpeechToText.SAMPLE_RATE * 2))
                .put("listening", listener.available()).put("speaking", speaker.available()));
        phrases.warm(settings.get());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection c = connections.get(session.getId());
        if (c == null) {
            return;
        }
        JsonNode node;
        try {
            node = json.readTree(message.getPayload());
        } catch (JacksonException e) {
            return;
        }
        switch (node.path("type").asString("")) {
            case "utterance_start" -> {
                interrupt(c, false); // speaking over the answer is the way to interrupt it
                synchronized (c.pcm) {
                    dropLive(c);
                    c.pcm.reset();
                    c.receiving = true;
                    c.tooLong = false;
                    c.live = listenLive();
                }
                c.speak = node.path("speak").asBoolean(true);
                if (node.hasNonNull("conversationId")) {
                    c.conversationId = node.get("conversationId").asString();
                }
            }
            case "utterance_end" -> endOfUtterance(c);
            case "utterance_cancel" -> {
                synchronized (c.pcm) {
                    c.receiving = false;
                    c.pcm.reset();
                    dropLive(c);
                }
            }
            case "interrupt" -> interrupt(c, true);
            case "wake" -> acknowledge(c);
            case "ping" -> { } // a door screen waiting for its wake word keeps the connection alive
            default -> log.debug("Ignoring an unknown voice message");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Connection c = connections.get(session.getId());
        if (c == null) {
            return;
        }
        synchronized (c.pcm) {
            if (!c.receiving || c.tooLong) {
                return;
            }
            byte[] frame = new byte[message.getPayloadLength()];
            message.getPayload().get(frame);
            if (c.pcm.size() + frame.length > maxBytes) {
                c.tooLong = true;
                return;
            }
            c.pcm.writeBytes(frame);
            if (c.live != null) {
                c.live.accept(frame);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection c = connections.remove(session.getId());
        if (c != null) {
            c.cancelled.set(true);
            synchronized (c.pcm) {
                dropLive(c);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Voice transport error: {}", exception.toString());
    }

    // ---- a turn ----

    private void endOfUtterance(Connection c) {
        byte[] audio;
        boolean tooLong;
        SpeechToText.Live live;
        synchronized (c.pcm) {
            if (!c.receiving) {
                return;
            }
            c.receiving = false;
            audio = c.pcm.toByteArray();
            tooLong = c.tooLong;
            c.pcm.reset();
            live = c.live;
            c.live = null;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        c.cancelled = cancelled;
        Thread.ofVirtual().name("voice-turn").start(() -> turn(c, audio, tooLong, live, cancelled));
    }

    private void interrupt(Connection c, boolean tell) {
        boolean running = !c.cancelled.get();
        c.cancelled.set(true);
        if (tell && running) {
            send(c, json.createObjectNode().put("type", "interrupted"));
            state(c, "idle");
        }
    }

    private void turn(Connection c, byte[] audio, boolean tooLong, SpeechToText.Live live, AtomicBoolean cancelled) {
        long begun = System.nanoTime();
        try {
            if (tooLong) {
                giveUp(live);
                send(c, error("That was too long, please say it in a shorter sentence"));
                return;
            }
            if (audio.length < MIN_BYTES || Pcm.peak(audio) < SILENCE_PEAK) {
                giveUp(live);
                log.info("Heard no speech: {} ms of audio, peak {}", Pcm.millis(audio.length, SpeechToText.SAMPLE_RATE), Pcm.peak(audio));
                send(c, json.createObjectNode().put("type", "no_speech"));
                return;
            }
            state(c, "transcribing");
            try {
                voice.checkLimit(c.who);
            } catch (RuntimeException e) {
                giveUp(live);
                throw e;
            }
            String text = transcribe(audio, live);
            long heard = millisSince(begun);
            if (cancelled.get()) {
                return;
            }
            if (text.isBlank()) {
                log.info("The transcription was empty ({} ms of audio)", Pcm.millis(audio.length, SpeechToText.SAMPLE_RATE));
                send(c, json.createObjectNode().put("type", "no_speech"));
                return;
            }
            send(c, json.createObjectNode().put("type", "transcript").put("text", text));

            state(c, "thinking");
            VoiceService.Reply reply = voice.converse(text, c.who, c.conversationId);
            long answered = millisSince(begun);
            c.conversationId = reply.conversationId();
            ObjectNode message = json.createObjectNode().put("type", "reply").put("reply", reply.reply())
                    .put("conversationId", reply.conversationId());
            var actions = message.putArray("actions");
            reply.actions().forEach(actions::add);
            var failed = message.putArray("failed");
            reply.failed().forEach(failed::add);
            send(c, message);

            VoiceSettings voiceSettings = settings.get();
            long firstAudio = -1;
            if (c.speak && voiceSettings.speakReplies() && speaker.available() && !cancelled.get()) {
                firstAudio = speak(c, reply.reply(), voiceSettings, cancelled, begun);
            }
            log.info("Voice timing after the end of speech: {} ms of audio transcribed in {} ms, understood and done by {} ms, first sound at {} ms",
                    Pcm.millis(audio.length, SpeechToText.SAMPLE_RATE), heard, answered, firstAudio < 0 ? "-" : firstAudio);
        } catch (ResponseStatusException | SpeechException e) {
            send(c, error(e instanceof ResponseStatusException r ? String.valueOf(r.getReason()) : e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("A voice turn failed: {}", e.toString());
            send(c, error("Something went wrong"));
        } finally {
            if (!cancelled.get()) {
                state(c, "idle");
            }
        }
    }

    /** Starts hearing the sentence as it is spoken; null when that is not possible, which the turn copes with. */
    private SpeechToText.Live listenLive() {
        try {
            return listener.available() ? listener.listenLive(settings.get().languages(), voice.vocabulary()) : null;
        } catch (RuntimeException e) {
            log.info("Live listening could not start: {}", e.toString());
            return null;
        }
    }

    private void dropLive(Connection c) {
        giveUp(c.live);
        c.live = null;
    }

    private static void giveUp(SpeechToText.Live live) {
        if (live != null) {
            live.cancel();
        }
    }

    /** The text of the sentence: from the live listening when it worked, otherwise from the whole recording. */
    private String transcribe(byte[] audio, SpeechToText.Live live) {
        if (live == null) {
            return whole(audio);
        }
        CompletableFuture<String> fromLive = CompletableFuture.supplyAsync(live::finish, VIRTUAL);
        String heard = within(fromLive, LIVE_PATIENCE_MS);
        if (heard == null && fromLive.isDone()) {
            log.info("Live listening gave no text, transcribing the recording instead");
            return whole(audio);
        }
        if (heard == null) {
            log.info("Live listening is slow, transcribing the recording as well");
            try {
                String text = whole(audio);
                live.cancel();
                return text;
            } catch (SpeechException e) {
                heard = within(fromLive, 3000); // the recording failed too: the live text may still come
                if (heard == null) {
                    throw e;
                }
            }
        }
        if (compare) {
            String text = whole(audio);
            log.info("Transcripts {}: live \"{}\", whole recording \"{}\"", heard.equals(text) ? "agree" : "differ", heard, text);
        }
        return heard;
    }

    private String whole(byte[] audio) {
        return listener.transcribe(audio, settings.get().languages(), voice.vocabulary());
    }

    /** What the future gives within the time, or null if it has not given anything (yet), failed, or gave null. */
    private static String within(CompletableFuture<String> future, long millis) {
        try {
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            return null;
        }
    }

    /**
     * Says what the person set to be said after the wake word ("Yes?"), so they know it is listening. It is only played from
     * memory: one that had to be made first would arrive after the person had started talking, so then nothing is said, and
     * it is made ready for next time.
     */
    private void acknowledge(Connection c) {
        VoiceSettings voiceSettings = settings.get();
        String reply = voiceSettings.wakeReply();
        if (reply == null || reply.isBlank() || !voiceSettings.speakReplies() || !speaker.available()) {
            return;
        }
        Thread.ofVirtual().name("voice-ack").start(() -> {
            try {
                boolean[] started = {false};
                boolean played = phrases.speakIfReady(reply, voiceSettings.voice(), voiceSettings.style(), whole -> {
                    started[0] = true;
                    byte[] pcm = Pcm.trimSilence(whole, TextToSpeech.SAMPLE_RATE); // the person is about to speak: no dead air
                    send(c, json.createObjectNode().put("type", "ack").put("sampleRate", TextToSpeech.SAMPLE_RATE));
                    for (int at = 0; at < pcm.length; at += OUT_FRAME) {
                        if (!sendBinary(c, Arrays.copyOfRange(pcm, at, Math.min(pcm.length, at + OUT_FRAME)))) {
                            return false;
                        }
                    }
                    return true;
                });
                if (!played) {
                    phrases.warm(voiceSettings);
                } else if (started[0]) {
                    send(c, json.createObjectNode().put("type", "ack_end"));
                }
            } catch (RuntimeException e) {
                log.debug("Could not acknowledge the wake word: {}", e.toString());
            }
        });
    }

    private static long millisSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }

    /** Speaks the answer; returns when the first sound was sent, in milliseconds since {@code begun}, or -1 if none was. */
    private long speak(Connection c, String text, VoiceSettings voiceSettings, AtomicBoolean cancelled, long begun) {
        state(c, "speaking");
        long[] first = {-1};
        boolean started = false;
        try {
            for (String sentence : SentenceSplitter.split(text)) {
                if (cancelled.get()) {
                    break;
                }
                if (!started) {
                    send(c, json.createObjectNode().put("type", "audio_start").put("sampleRate", TextToSpeech.SAMPLE_RATE));
                    started = true;
                }
                phrases.speak(sentence, voiceSettings.voice(), voiceSettings.style(), pcm -> {
                    if (first[0] < 0) {
                        first[0] = millisSince(begun);
                    }
                    for (int at = 0; at < pcm.length; at += OUT_FRAME) {
                        if (cancelled.get() || !sendBinary(c, Arrays.copyOfRange(pcm, at, Math.min(pcm.length, at + OUT_FRAME)))) {
                            return false;
                        }
                    }
                    return !cancelled.get();
                });
            }
        } finally {
            if (started && !cancelled.get()) {
                send(c, json.createObjectNode().put("type", "audio_end"));
            }
        }
        return first[0];
    }

    // ---- output ----

    private ObjectNode error(String message) {
        return json.createObjectNode().put("type", "error").put("message", message);
    }

    private void state(Connection c, String state) {
        send(c, json.createObjectNode().put("type", "state").put("state", state));
    }

    private void send(Connection c, ObjectNode message) {
        try {
            c.out.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (IOException | RuntimeException e) {
            c.cancelled.set(true); // the page went away or cannot keep up
        }
    }

    private boolean sendBinary(Connection c, byte[] frame) {
        try {
            c.out.sendMessage(new BinaryMessage(frame));
            return true;
        } catch (IOException | RuntimeException e) {
            c.cancelled.set(true);
            return false;
        }
    }
}
