package dev.kuba.homedisplay.voice;

import java.util.List;

/** Turns a recorded sentence into text. */
public interface SpeechToText {

    /** False when no API key is configured. */
    boolean available();

    /**
     * @param pcm        16-bit little-endian mono audio at {@link #SAMPLE_RATE}
     * @param languages  BCP-47 hints such as pl-PL and en-US; empty lets the service detect the language
     * @param vocabulary names the speaker is likely to use (light, room and scene names), to help the service spell them
     * @return what was said, or an empty string when nothing intelligible was heard
     * @throws SpeechException when the service could not be reached or refused
     */
    String transcribe(byte[] pcm, List<String> languages, List<String> vocabulary);

    /**
     * Starts hearing a sentence while it is still being spoken, so that most of the work is done when it ends.
     *
     * @return null when this service cannot do that; the caller then sends the whole recording to {@link #transcribe}
     */
    default Live listenLive(List<String> languages, List<String> vocabulary) {
        return null;
    }

    /** A sentence being heard as it is spoken. Use it once. */
    interface Live {

        /** The next piece of audio, in the format of {@link #transcribe}. Never blocks. */
        void accept(byte[] pcm);

        /**
         * The audio has ended: waits for the text.
         *
         * @return what was said, an empty string when nothing intelligible was heard, or null when it could not be told
         *         (the connection failed or took too long): the caller should then use {@link #transcribe}
         */
        String finish();

        /** Gives up, for a sentence that is not going to be used. */
        void cancel();
    }

    int SAMPLE_RATE = 16_000;
}
