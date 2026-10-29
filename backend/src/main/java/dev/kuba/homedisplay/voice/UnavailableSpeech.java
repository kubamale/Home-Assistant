package dev.kuba.homedisplay.voice;

import java.util.List;

/** Stands in for listening and speaking when no Gemini API key is configured. */
class UnavailableSpeech implements SpeechBackend {

    private static SpeechException notSetUp() {
        return new SpeechException("Voice is not set up yet: the server needs a GEMINI_API_KEY");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String transcribe(byte[] pcm, List<String> languages, List<String> vocabulary) {
        throw notSetUp();
    }

    @Override
    public void speak(String text, String voice, String style, AudioSink sink) {
        throw notSetUp();
    }
}
