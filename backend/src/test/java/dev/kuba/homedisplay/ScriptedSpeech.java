package dev.kuba.homedisplay;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.kuba.homedisplay.voice.SpeechException;
import dev.kuba.homedisplay.voice.SpeechToText;
import dev.kuba.homedisplay.voice.TextToSpeech;

/** A stand-in for Gemini: hears what the test says it heard, and "speaks" fixed pieces of audio. */
public class ScriptedSpeech implements SpeechToText, TextToSpeech {

    public record Spoken(String text, String voice, String style) {
    }

    public volatile String transcript = "turn on the lamp";
    public volatile RuntimeException listenFailure;
    public volatile RuntimeException speakFailure;
    public volatile boolean available = true;
    /** When set, speaking pauses after the first piece of audio until the test opens the gate. */
    public volatile CountDownLatch gate;
    public volatile boolean stoppedBySink;
    /** When true, sentences are heard as they are spoken (null from {@link #liveTranscript} means that failed). */
    public volatile boolean live;
    public volatile String liveTranscript = "turn on the lamp";
    /** How long the live listening takes to give its text, for a slow service. */
    public volatile long liveDelayMillis;

    public final AtomicInteger listens = new AtomicInteger();
    public final AtomicInteger liveStarts = new AtomicInteger();
    public final AtomicInteger liveFinishes = new AtomicInteger();
    public final AtomicInteger liveCancels = new AtomicInteger();
    public final AtomicInteger liveBytes = new AtomicInteger();
    public final List<List<String>> liveLanguages = new CopyOnWriteArrayList<>();
    public final List<Integer> heardBytes = new CopyOnWriteArrayList<>();
    public final List<List<String>> heardLanguages = new CopyOnWriteArrayList<>();
    public final List<List<String>> heardVocabulary = new CopyOnWriteArrayList<>();
    public final List<Spoken> spoken = new CopyOnWriteArrayList<>();

    /** Each spoken sentence is this many pieces of 1000 bytes. */
    public static final int PIECES = 3;

    public void reset() {
        transcript = "turn on the lamp";
        listenFailure = null;
        speakFailure = null;
        available = true;
        gate = null;
        stoppedBySink = false;
        live = false;
        liveTranscript = "turn on the lamp";
        liveDelayMillis = 0;
        liveStarts.set(0);
        liveFinishes.set(0);
        liveCancels.set(0);
        liveBytes.set(0);
        liveLanguages.clear();
        listens.set(0);
        heardBytes.clear();
        heardLanguages.clear();
        heardVocabulary.clear();
        spoken.clear();
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String transcribe(byte[] pcm, List<String> languages, List<String> vocabulary) {
        listens.incrementAndGet();
        if (listenFailure != null) {
            throw listenFailure;
        }
        heardBytes.add(pcm.length);
        heardLanguages.add(languages);
        heardVocabulary.add(vocabulary);
        return transcript;
    }

    @Override
    public Live listenLive(List<String> languages, List<String> vocabulary) {
        if (!live) {
            return null;
        }
        liveStarts.incrementAndGet();
        liveLanguages.add(languages);
        return new Live() {
            @Override
            public void accept(byte[] pcm) {
                liveBytes.addAndGet(pcm.length);
            }

            @Override
            public String finish() {
                liveFinishes.incrementAndGet();
                if (liveDelayMillis > 0) {
                    try {
                        Thread.sleep(liveDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return liveTranscript;
            }

            @Override
            public void cancel() {
                liveCancels.incrementAndGet();
            }
        };
    }

    @Override
    public void speak(String text, String voice, String style, AudioSink sink) {
        spoken.add(new Spoken(text, voice, style));
        if (speakFailure != null) {
            throw speakFailure;
        }
        for (int i = 0; i < PIECES; i++) {
            if (!sink.accept(new byte[1000])) {
                stoppedBySink = true;
                return;
            }
            CountDownLatch waiting = gate;
            if (i == 0 && waiting != null) {
                try {
                    waiting.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SpeechException("Interrupted");
                }
            }
        }
    }
}
