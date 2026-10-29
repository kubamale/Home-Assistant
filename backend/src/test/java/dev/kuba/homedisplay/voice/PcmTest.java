package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class PcmTest {

    private static final int RATE = 24_000;

    /** Seconds of silence, then of a loud tone, then of silence. */
    private static byte[] clip(double before, double sound, double after) {
        int total = (int) ((before + sound + after) * RATE);
        ByteBuffer out = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < total; i++) {
            double t = i / (double) RATE;
            boolean loud = t >= before && t < before + sound;
            out.putShort((short) (loud ? 12000 * Math.sin(2 * Math.PI * 220 * t) : 0));
        }
        return out.array();
    }

    @Test
    void silenceBeforeAndAfterTheSoundIsCutOffLeavingAShortMargin() {
        byte[] trimmed = Pcm.trimSilence(clip(0.4, 0.5, 0.3), RATE);
        long millis = Pcm.millis(trimmed.length, RATE);
        assertTrue(millis >= 600 && millis <= 640, "sound 500 ms plus about 50 ms either side, was " + millis + " ms");
        assertTrue(Pcm.peak(trimmed) > 0.3, "the sound is all still there");
    }

    @Test
    void aClipWithoutSilenceOrWithoutSoundIsLeftAlone() {
        byte[] tight = clip(0, 0.5, 0);
        assertEquals(tight.length, Pcm.trimSilence(tight, RATE).length);
        byte[] nothing = clip(1, 0, 0);
        assertArrayEquals(nothing, Pcm.trimSilence(nothing, RATE));
    }

    @Test
    void aShortOrOddLengthClipDoesNotBreak() {
        assertEquals(0, Pcm.trimSilence(new byte[0], RATE).length);
        assertEquals(3, Pcm.trimSilence(new byte[3], RATE).length);
    }
}
