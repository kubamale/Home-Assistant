package dev.kuba.homedisplay.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts an answer into pieces for the speech service, one request each, so the first piece can be heard while the
 * rest is still being made. Very short sentences are joined to a neighbour (each request has a fixed cost) and very
 * long ones are cut at a comma or a space.
 */
final class SentenceSplitter {

    private static final int JOIN_BELOW = 20;
    private static final int LONGEST = 280;

    private SentenceSplitter() {
    }

    static List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        if (text == null) {
            return pieces;
        }
        for (String paragraph : text.strip().split("\\R+")) {
            for (String sentence : paragraph.strip().split("(?<=[.!?…])\\s+")) {
                String s = sentence.strip();
                if (!s.isEmpty()) {
                    addCapped(pieces, s);
                }
            }
        }
        List<String> merged = new ArrayList<>();
        for (String piece : pieces) {
            int last = merged.size() - 1;
            if (last >= 0 && (merged.get(last).length() < JOIN_BELOW)) {
                merged.set(last, merged.get(last) + " " + piece);
            } else {
                merged.add(piece);
            }
        }
        // a short last piece joins the one before it
        int n = merged.size();
        if (n >= 2 && merged.get(n - 1).length() < JOIN_BELOW) {
            merged.set(n - 2, merged.get(n - 2) + " " + merged.remove(n - 1));
        }
        return merged;
    }

    private static void addCapped(List<String> out, String sentence) {
        String rest = sentence;
        while (rest.length() > LONGEST) {
            int cut = rest.lastIndexOf(',', LONGEST);
            if (cut < LONGEST / 2) {
                cut = rest.lastIndexOf(' ', LONGEST);
            }
            if (cut <= 0) {
                cut = LONGEST;
            }
            out.add(rest.substring(0, cut + (rest.charAt(cut) == ',' ? 1 : 0)).strip());
            rest = rest.substring(cut + 1).strip();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
    }
}
