package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.kuba.homedisplay.config.AppProperties;

/** Short sentences are remembered per voice and style; long ones and interrupted ones are not. */
class SpokenPhrasesTest {

    /** Counts how often it is asked, and speaks two pieces of audio. */
    private static final class CountingSpeech implements TextToSpeech {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> texts = new ArrayList<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public synchronized void speak(String text, String voice, String style, AudioSink sink) {
            calls.incrementAndGet();
            texts.add(text);
            if (sink.accept(new byte[] {1, 2}) ) {
                sink.accept(new byte[] {3, 4});
            }
        }
    }

    private static AppProperties props(boolean warm) {
        return new AppProperties(null, null, null, null, null, new AppProperties.Speech("k", "m", "SMART", "t", true, "l", true, false, 15, warm, null));
    }

    private static byte[] heard(SpokenPhrases phrases, String text, String voice, String style) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        phrases.speak(text, voice, style, pcm -> {
            out.writeBytes(pcm);
            return true;
        });
        return out.toByteArray();
    }

    @Test
    void aShortSentenceIsMadeOnceAndReplayedAfterwards() {
        CountingSpeech speech = new CountingSpeech();
        SpokenPhrases phrases = new SpokenPhrases(speech, props(false));

        byte[] first = heard(phrases, "Done.", "Kore", "");
        byte[] second = heard(phrases, "Done.", "Kore", "");

        assertArrayEquals(new byte[] {1, 2, 3, 4}, first);
        assertArrayEquals(first, second);
        assertEquals(1, speech.calls.get());
    }

    @Test
    void anotherVoiceOrStyleOrTextIsNotTheSameSentence() {
        CountingSpeech speech = new CountingSpeech();
        SpokenPhrases phrases = new SpokenPhrases(speech, props(false));

        heard(phrases, "Done.", "Kore", "");
        heard(phrases, "Done.", "Puck", "");
        heard(phrases, "Done.", "Kore", "calm");
        heard(phrases, "Gotowe.", "Kore", "");
        heard(phrases, "Done.", "Kore", "  ");   // blank style is no style

        assertEquals(4, speech.calls.get());
    }

    @Test
    void aLongSentenceIsNeverKept() {
        CountingSpeech speech = new CountingSpeech();
        SpokenPhrases phrases = new SpokenPhrases(speech, props(false));
        String long_ = "The background light is now blue at fifty percent brightness.";

        heard(phrases, long_, "Kore", "");
        heard(phrases, long_, "Kore", "");

        assertEquals(2, speech.calls.get());
    }

    @Test
    void aSentenceTheListenerInterruptedIsNotKept() {
        CountingSpeech speech = new CountingSpeech();
        SpokenPhrases phrases = new SpokenPhrases(speech, props(false));

        phrases.speak("Done.", "Kore", "", pcm -> false); // stopped after the first piece
        byte[] again = heard(phrases, "Done.", "Kore", "");

        assertEquals(2, speech.calls.get());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, again); // the whole thing this time
    }

    @Test
    void aFailureLeavesNothingBehind() {
        AtomicInteger calls = new AtomicInteger();
        TextToSpeech flaky = new TextToSpeech() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void speak(String text, String voice, String style, AudioSink sink) {
                if (calls.incrementAndGet() == 1) {
                    sink.accept(new byte[] {9, 9}); // some audio, then the service gives up
                    throw new SpeechException("down");
                }
                sink.accept(new byte[] {1, 2});
            }
        };
        SpokenPhrases phrases = new SpokenPhrases(flaky, props(false));
        try {
            heard(phrases, "Done.", "Kore", "");
        } catch (SpeechException expected) {
            // the answer is lost, but the half sentence must not be remembered
        }
        assertArrayEquals(new byte[] {1, 2}, heard(phrases, "Done.", "Kore", ""));
        assertEquals(2, calls.get());
    }

    @Test
    void theFixedAnswersAndTheWakeReplyAreMadeAheadOfTime() throws Exception {
        CountingSpeech speech = new CountingSpeech();
        SpokenPhrases phrases = new SpokenPhrases(speech, props(true));
        VoiceSettings settings = new VoiceSettings("Kore", "", List.of("en-US"), true, "normal", "Yes?");

        phrases.warm(settings);
        for (int waited = 0; speech.calls.get() < 3 && waited < 50; waited++) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertEquals(3, speech.calls.get());
        assertTrue(speech.texts.containsAll(SpokenPhrases.FIXED));
        assertTrue(speech.texts.contains("Yes?"));

        heard(phrases, "Done.", "Kore", "");
        heard(phrases, "Gotowe.", "Kore", "");
        heard(phrases, "Yes?", "Kore", "");
        assertEquals(3, speech.calls.get(), "answered from memory");

        phrases.warm(settings); // nothing left to make
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(3, speech.calls.get());
    }

    @Test
    void noReplyIsMadeForAWakeReplyThatIsEmptyTooLongOrMutedAway() throws Exception {
        for (VoiceSettings settings : List.of(new VoiceSettings("Kore", "", List.of(), true, "normal", ""),
                new VoiceSettings("Kore", "", List.of(), true, "normal", "x".repeat(41)),
                new VoiceSettings("Kore", "", List.of(), false, "normal", "Yes?"))) {
            CountingSpeech speech = new CountingSpeech();
            new SpokenPhrases(speech, props(true)).warm(settings);
            for (int waited = 0; speech.calls.get() < 2 && waited < 30; waited++) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            TimeUnit.MILLISECONDS.sleep(150);
            assertEquals(2, speech.calls.get(), settings.toString());
        }
    }

    @Test
    void aSentenceIsOnlySpokenIfReadyWhenItIsInMemoryAndNeverMadeOnTheSpot() throws Exception {
        CountingSpeech speech = new CountingSpeech();
        SpokenPhrases phrases = new SpokenPhrases(speech, props(false));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        assertFalse(phrases.speakIfReady("Yes?", "Kore", "", pcm -> {
            out.writeBytes(pcm);
            return true;
        }));
        assertEquals(0, speech.calls.get());
        assertEquals(0, out.size());

        heard(phrases, "Yes?", "Kore", ""); // said once, so remembered
        assertTrue(phrases.speakIfReady("Yes?", "Kore", "", pcm -> {
            out.writeBytes(pcm);
            return true;
        }));
        assertTrue(out.size() > 0);
        assertEquals(1, speech.calls.get());
        assertFalse(phrases.speakIfReady("Yes?", "Puck", "", pcm -> true), "another voice has not been made");
    }

    @Test
    void warmingCanBeSwitchedOff() throws Exception {
        CountingSpeech speech = new CountingSpeech();
        new SpokenPhrases(speech, props(false)).warm(new VoiceSettings("Kore", "", List.of("en-US"), true, "normal", "Yes?"));
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(0, speech.calls.get());
    }
}
