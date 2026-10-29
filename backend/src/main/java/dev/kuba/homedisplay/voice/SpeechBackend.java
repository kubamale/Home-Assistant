package dev.kuba.homedisplay.voice;

/**
 * A speech service that both listens and speaks. It is registered as one bean, so that injecting either
 * {@link SpeechToText} or {@link TextToSpeech} finds exactly one candidate.
 */
interface SpeechBackend extends SpeechToText, TextToSpeech {

    @Override
    boolean available();
}
