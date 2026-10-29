package dev.kuba.homedisplay.voice;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.kuba.homedisplay.voice.AgentModel.ToolCall;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Understands the plainest spoken commands without asking the model: "turn off the background light", "włącz
 * wszystkie światła", "turn on the Movie scene". That saves the second or so a model call takes.
 *
 * <p>It is deliberately narrow. It acts only when it can account for <em>every</em> word of the sentence and the
 * words point at exactly one light, one scene or "all the lights". Anything else (a colour, a brightness, two
 * lights, a question, a name it is not sure of, a typo) is left to the model, which understands everything this
 * does not. A command it does take goes through the same tools as the model's, so the same checks apply.
 */
final class QuickCommands {

    /** What to run, and whether the person spoke Polish (so the answer can be too). */
    record Plan(ToolCall call, boolean polish) {
    }

    private enum Direction { ON, OFF, SCENE }

    private static final Set<String> POLITE = Set.of("please", "prosze", "hey", "hej", "jarvis", "ok", "okay", "now", "teraz", "dobra", "dobrze", "no");
    private static final Set<String> ON_VERBS = Set.of("wlacz", "zapal", "odpal");
    private static final Set<String> OFF_VERBS = Set.of("wylacz", "zgas", "wygas");
    private static final Set<String> SCENE_VERBS = Set.of("apply", "activate", "run", "start", "play", "set", "uruchom", "aktywuj", "ustaw");
    private static final Set<String> PARTICLE_VERBS = Set.of("turn", "switch");

    /** Words that only point at "a light" in general, so they say nothing about which one. */
    private static final Set<String> NOISE = Set.of("the", "a", "an", "my", "to", "of", "mi", "moje", "moja", "moj", "light", "lights",
            "lamp", "lamps", "swiatlo", "swiatla", "swiatlem", "lampa", "lampe", "lampke", "lampka", "lampy", "lampki");
    private static final Set<String> SCENE_WORDS = Set.of("scene", "scenes", "scena", "sceny", "scenie", "theme", "motyw", "tryb", "mode");
    private static final Set<String> ALL_WORDS = Set.of("all", "every", "everything", "wszystkie", "wszystko", "wszystkich");
    /** "All" reaches beyond the room the person is in when they say one of these. */
    private static final Set<String> EVERYWHERE = Set.of("everywhere", "wszedzie", "house", "home", "flat", "apartment", "whole", "entire",
            "dom", "domu", "mieszkanie", "mieszkaniu", "caly", "cala", "cale");

    /** What the two channels of a light bar are called in everyday speech, by channel id. */
    private static final Map<String, List<String>> CHANNEL_WORDS = Map.of(
            "background", List.of("tylne", "tylna", "tylny", "rear", "back", "behind"),
            "main", List.of("glowne", "glowna", "glowny", "przednie", "przednia", "front"));

    private final JsonMapper json;

    QuickCommands(JsonMapper json) {
        this.json = json;
    }

    /**
     * @param roomId the room of the display that was spoken to, or null when it is in none (or it was a phone). "All the
     *               lights" then means that room's lights, and when several lights fit a name the one in that room is meant
     */
    Optional<Plan> plan(String text, HomeCatalog catalog, Long roomId) {
        List<String> words = new ArrayList<>(words(text));
        while (!words.isEmpty() && POLITE.contains(words.get(0))) {
            words.remove(0);
        }
        while (!words.isEmpty() && POLITE.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        if (words.size() < 2) {
            return Optional.empty();
        }

        String verb = words.remove(0);
        Direction direction;
        boolean polish = false;
        if (PARTICLE_VERBS.contains(verb)) {
            int on = words.indexOf("on");
            int off = words.indexOf("off");
            if (on < 0 && off < 0) {
                return Optional.empty();
            }
            // If both appear the first one decides and the other is left over, which the sentence cannot be understood with.
            int particle = on < 0 ? off : off < 0 ? on : Math.min(on, off);
            direction = "on".equals(words.remove(particle)) ? Direction.ON : Direction.OFF;
        } else if (ON_VERBS.contains(verb)) {
            direction = Direction.ON;
            polish = true;
        } else if (OFF_VERBS.contains(verb)) {
            direction = Direction.OFF;
            polish = true;
        } else if (SCENE_VERBS.contains(verb)) {
            direction = Direction.SCENE;
            polish = Set.of("uruchom", "aktywuj", "ustaw").contains(verb);
        } else {
            return Optional.empty();
        }

        boolean sceneWord = words.stream().anyMatch(SCENE_WORDS::contains);
        List<String> object = words.stream().filter(w -> !NOISE.contains(w) && !SCENE_WORDS.contains(w)).distinct().toList();
        if (object.isEmpty()) {
            return Optional.empty();
        }

        boolean everywhere = object.stream().anyMatch(EVERYWHERE::contains);
        if (!sceneWord && direction != Direction.SCENE && object.stream().allMatch(w -> ALL_WORDS.contains(w) || EVERYWHERE.contains(w))
                && catalog.lights().stream().noneMatch(l -> object.stream().anyMatch(w -> keywords(l).stream().anyMatch(k -> same(w, k))))) {
            return everything(catalog, direction == Direction.ON, polish, everywhere ? null : roomId);
        }

        List<HomeCatalog.Light> fitting = new ArrayList<>();
        if (!sceneWord && direction != Direction.SCENE) {
            for (HomeCatalog.Light light : catalog.lights()) {
                if (matches(object, keywords(light))) {
                    fitting.add(light);
                }
            }
        }
        List<HomeCatalog.Light> lights = inTheRoom(fitting, roomId);
        List<ToolCall> scenes = new ArrayList<>();
        if (direction != Direction.OFF) { // there is no such thing as switching a scene off
            for (HomeCatalog.SceneRef scene : catalog.scenes()) {
                if (matches(object, words(scene.name()))) {
                    scenes.add(new ToolCall("quick", VoiceTools.APPLY_SCENE, json.createObjectNode().put("scene", scene.ref())));
                }
            }
        }
        if (lights.isEmpty() && scenes.size() == 1) {
            return Optional.of(new Plan(scenes.get(0), polish));
        }
        // Naming a device without saying which of its channels means all of them ("the lamp above the monitor").
        if (scenes.isEmpty() && oneDevice(lights)) {
            return Optional.of(new Plan(setLights(lights.stream().map(HomeCatalog.Light::ref).toList(), direction == Direction.ON), polish));
        }
        return Optional.empty();
    }

    private static boolean oneDevice(List<HomeCatalog.Light> lights) {
        return !lights.isEmpty() && lights.stream().allMatch(l -> l.deviceId().equals(lights.get(0).deviceId()));
    }

    /** When lights of several devices fit a name, the ones in the room the person is in, if there are any. */
    private static List<HomeCatalog.Light> inTheRoom(List<HomeCatalog.Light> fitting, Long roomId) {
        if (roomId == null || fitting.isEmpty() || oneDevice(fitting)) {
            return fitting;
        }
        List<HomeCatalog.Light> here = fitting.stream().filter(l -> roomId.equals(l.roomId())).toList();
        return here.isEmpty() ? fitting : here;
    }

    /** All the lights of a room, or of the whole home when no room is given. A room without lights is left to the model. */
    private Optional<Plan> everything(HomeCatalog catalog, boolean on, boolean polish, Long roomId) {
        List<String> refs = catalog.lights().stream().filter(l -> roomId == null || roomId.equals(l.roomId()))
                .map(HomeCatalog.Light::ref).toList();
        return refs.isEmpty() ? Optional.empty() : Optional.of(new Plan(setLights(refs, on), polish));
    }

    private ToolCall setLights(List<String> refs, boolean on) {
        ObjectNode input = json.createObjectNode();
        ArrayNode changes = input.putArray("changes");
        refs.forEach(ref -> changes.addObject().put("light", ref).put("on", on));
        return new ToolCall("quick", VoiceTools.SET_LIGHTS, input);
    }

    /** The words that can stand for a light: its name without the generic ones, and what its channel is called in speech. */
    private static List<String> keywords(HomeCatalog.Light light) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        words(light.name()).stream().filter(w -> !NOISE.contains(w)).forEach(out::add);
        out.addAll(CHANNEL_WORDS.getOrDefault(light.channel(), List.of()));
        return List.copyOf(out);
    }

    /** True when every spoken word is (nearly) one of the candidate's words. */
    private static boolean matches(List<String> spoken, List<String> candidate) {
        return spoken.stream().allMatch(s -> candidate.stream().anyMatch(c -> same(s, c)));
    }

    /**
     * Equal, or the same word with a different ending of up to three letters, which is how Polish names change when
     * spoken ("tylna", "tylne", "tylnego"). Short words and anything with a digit must match exactly.
     */
    static boolean same(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() < 4 || b.length() < 4 || hasDigit(a) || hasDigit(b)) {
            return false;
        }
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= 4 && common >= Math.max(a.length(), b.length()) - 3;
    }

    private static boolean hasDigit(String word) {
        return word.chars().anyMatch(Character::isDigit);
    }

    /** Lower case, without accents, split into letters-and-digits words. */
    static List<String> words(String text) {
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return Arrays.stream(plain.split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
    }
}
