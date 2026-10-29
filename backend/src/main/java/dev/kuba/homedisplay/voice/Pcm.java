package dev.kuba.homedisplay.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Small helpers for 16-bit mono PCM. */
final class Pcm {

    private Pcm() {
    }

    /** Wraps raw PCM in a WAV header, which is how most services want to receive a recording. */
    static byte[] wav(byte[] pcm, int sampleRate) {
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return out.array();
    }

    /** The loudest sample as a fraction of full scale, 0 to 1: a recording of silence peaks near 0. */
    static double peak(byte[] pcm) {
        int max = 0;
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        while (in.remaining() >= 2) {
            max = Math.max(max, Math.abs(in.getShort()));
        }
        return max / 32768.0;
    }

    /**
     * The audio without the silence before and after the sound, keeping a little of it (50 ms) so nothing is clipped. The
     * speech service puts about a third of a second of silence on each side of a word, which makes a one-word answer feel
     * slow; a window counts as sound when its average loudness is over 1 % of full scale. Audio with no sound is returned as it is.
     */
    static byte[] trimSilence(byte[] pcm, int sampleRate) {
        int window = sampleRate / 50; // 20 ms in samples
        int samples = pcm.length / 2;
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int first = -1;
        int last = -1;
        for (int at = 0; at + window <= samples; at += window) {
            long sum = 0;
            for (int i = 0; i < window; i++) {
                sum += Math.abs(in.getShort((at + i) * 2));
            }
            if (sum / (double) window / 32768.0 > 0.01) {
                if (first < 0) {
                    first = at;
                }
                last = at + window;
            }
        }
        if (first < 0) {
            return pcm;
        }
        int margin = sampleRate / 20; // 50 ms
        int from = Math.max(0, first - margin);
        int to = Math.min(samples, last + margin);
        return Arrays.copyOfRange(pcm, from * 2, to * 2);
    }

    /** How long this much 16-bit mono audio lasts, in milliseconds. */
    static long millis(int bytes, int sampleRate) {
        return bytes * 1000L / (sampleRate * 2L);
    }

    /**
     * Hands audio on in whole samples. A network chunk can end in the middle of a 16-bit sample; the odd byte is
     * held back and joined to the next chunk, so the player never sees a torn sample.
     */
    static final class Aligner {
        private byte carry;
        private boolean hasCarry;

        byte[] push(byte[] chunk) {
            if (chunk.length == 0) {
                return chunk;
            }
            byte[] data = chunk;
            if (hasCarry) {
                data = new byte[chunk.length + 1];
                data[0] = carry;
                System.arraycopy(chunk, 0, data, 1, chunk.length);
                hasCarry = false;
            }
            if (data.length % 2 == 1) {
                carry = data[data.length - 1];
                hasCarry = true;
                return Arrays.copyOf(data, data.length - 1);
            }
            return data;
        }
    }
}
