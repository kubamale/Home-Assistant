package dev.kuba.homedisplay.voice;

/** Turns text into audio, handing the audio over in pieces as it is made. */
public interface TextToSpeech {

    /** 16-bit little-endian mono PCM at this rate, as delivered to the sink. */
    int SAMPLE_RATE = 24_000;

    /** Receives audio. Returning false stops the speech (the listener interrupted). */
    interface AudioSink {
        boolean accept(byte[] pcm);
    }

    /** False when no API key is configured. */
    boolean available();

    /**
     * Speaks {@code text} with the named voice. Blocks until the speech has been handed to the sink or the sink
     * stopped it.
     *
     * @param style optional delivery, such as "calm and friendly"; blank for the voice's own
     * @throws SpeechException when the service could not be reached or refused
     */
    void speak(String text, String voice, String style, AudioSink sink);
}
