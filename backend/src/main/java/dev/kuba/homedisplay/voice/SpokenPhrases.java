package dev.kuba.homedisplay.voice;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.kuba.homedisplay.config.AppProperties;

/**
 * Speaks short answers, remembering the audio of the ones it has said before. The speech service needs about two and a
 * half seconds to start, which is most of the wait after a plain command, but "Done." sounds the same every time. So
 * short sentences are kept (per voice and style) and replayed at once, and the fixed ones are made ahead of time.
 * Anything longer goes straight to the speech service.
 */
@Component
public class SpokenPhrases {

    private static final Logger log = LoggerFactory.getLogger(SpokenPhrases.class);

    /** What the assistant says after a plain command, in the two languages it knows. */
    static final List<String> FIXED = List.of(VoiceService.DONE, VoiceService.DONE_PL);

    private static final int MAX_LENGTH = 40;
    private static final int MAX_ENTRIES = 64;

    private final TextToSpeech speaker;
    private final boolean warming;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    SpokenPhrases(TextToSpeech speaker, AppProperties props) {
        this.speaker = speaker;
        this.warming = props.speech().warmPhrases();
    }

    /** As {@link TextToSpeech#speak}: from memory when this short sentence was said before with this voice and style. */
    void speak(String text, String voice, String style, TextToSpeech.AudioSink sink) {
        if (text.length() > MAX_LENGTH) {
            speaker.speak(text, voice, style, sink);
            return;
        }
        String key = key(text, voice, style);
        byte[] known;
        synchronized (cache) {
            known = cache.get(key);
        }
        if (known != null) {
            sink.accept(known);
            return;
        }
        ByteArrayOutputStream heard = new ByteArrayOutputStream();
        boolean[] stopped = {false};
        speaker.speak(text, voice, style, pcm -> {
            heard.writeBytes(pcm);
            boolean goOn = sink.accept(pcm);
            stopped[0] |= !goOn;
            return goOn;
        });
        if (!stopped[0] && heard.size() > 0) { // only whole sentences are worth keeping
            synchronized (cache) {
                cache.put(key, heard.toByteArray());
            }
        }
    }

    /**
     * Speaks a short sentence only if it is already remembered, which is instant, and says whether it did. A sentence that
     * still has to be made would arrive too late to be any use (the acknowledgement of a wake word).
     */
    boolean speakIfReady(String text, String voice, String style, TextToSpeech.AudioSink sink) {
        byte[] known;
        synchronized (cache) {
            known = cache.get(key(text, voice, style));
        }
        if (known == null) {
            return false;
        }
        sink.accept(known);
        return true;
    }

    /** Makes the fixed answers, and what is said after the wake word, in the background, so the first one is not slow either. */
    void warm(VoiceSettings settings) {
        if (!warming || !speaker.available()) {
            return;
        }
        Set<String> phrases = new java.util.LinkedHashSet<>(FIXED);
        String reply = settings.wakeReply();
        if (reply != null && !reply.isBlank() && reply.length() <= MAX_LENGTH && settings.speakReplies()) {
            phrases.add(reply.trim());
        }
        for (String phrase : phrases) {
            String key = key(phrase, settings.voice(), settings.style());
            boolean cached;
            synchronized (cache) {
                cached = cache.containsKey(key);
            }
            if (cached || !inFlight.add(key)) {
                continue;
            }
            Thread.ofVirtual().name("voice-warm").start(() -> {
                try {
                    speak(phrase, settings.voice(), settings.style(), pcm -> true);
                } catch (RuntimeException e) {
                    log.debug("Could not prepare \"{}\": {}", phrase, e.toString());
                } finally {
                    inFlight.remove(key);
                }
            });
        }
    }

    /** Forgets everything remembered (for tests). */
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static String key(String text, String voice, String style) {
        return voice + '\u0000' + (style == null ? "" : style.trim()) + '\u0000' + text;
    }
}
