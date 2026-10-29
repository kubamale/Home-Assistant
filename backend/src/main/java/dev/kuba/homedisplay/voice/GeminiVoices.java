package dev.kuba.homedisplay.voice;

import java.util.List;

/** The prebuilt voices of Gemini text-to-speech. The service picks the language from the text itself. */
public final class GeminiVoices {

    public static final String DEFAULT = "Kore";

    public static final List<String> NAMES = List.of("Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
            "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib",
            "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
            "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat");

    private GeminiVoices() {
    }
}
