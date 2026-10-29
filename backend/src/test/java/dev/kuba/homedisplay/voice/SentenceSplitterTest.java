package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SentenceSplitterTest {

    @Test
    void sentencesBecomePiecesAndTinyOnesJoinTheirNeighbours() {
        assertEquals(List.of("Done. The desk lamp is on and dimmed to twenty percent."),
                SentenceSplitter.split("Done. The desk lamp is on and dimmed to twenty percent."));
        assertEquals(List.of("It is twelve degrees and overcast right now.", "Rain is possible this afternoon, so take a jacket."),
                SentenceSplitter.split("It is twelve degrees and overcast right now. Rain is possible this afternoon, so take a jacket."));
    }

    @Test
    void aShortLastSentenceJoinsTheOneBeforeIt() {
        assertEquals(List.of("The background light is off and the main one is at forty percent. Anything else?"),
                SentenceSplitter.split("The background light is off and the main one is at forty percent. Anything else?"));
    }

    @Test
    void paragraphsAndQuestionsAreSplit() {
        assertEquals(List.of("First paragraph is long enough to stand alone.", "Second paragraph also stands alone here."),
                SentenceSplitter.split("First paragraph is long enough to stand alone.\n\nSecond paragraph also stands alone here."));
    }

    @Test
    void aVeryLongSentenceIsCutAtACommaOrSpace() {
        String longSentence = ("word ".repeat(40) + "end, ").repeat(3).strip();
        List<String> pieces = SentenceSplitter.split(longSentence);
        assertTrue(pieces.size() >= 2);
        pieces.forEach(p -> assertTrue(p.length() <= 285, "too long: " + p.length()));
        assertEquals(longSentence.replaceAll("\\s+", " "), String.join(" ", pieces).replaceAll("\\s+", " "));
    }

    @Test
    void nothingToSayIsNoPieces() {
        assertEquals(List.of(), SentenceSplitter.split("   "));
        assertEquals(List.of(), SentenceSplitter.split(null));
    }
}
