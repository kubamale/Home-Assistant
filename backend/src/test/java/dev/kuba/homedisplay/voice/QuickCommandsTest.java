package dev.kuba.homedisplay.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.kuba.homedisplay.devices.Capabilities;
import dev.kuba.homedisplay.devices.HomeChannel;
import dev.kuba.homedisplay.devices.HomeDevice;
import dev.kuba.homedisplay.rooms.Room;
import dev.kuba.homedisplay.scenes.SceneViews.SceneView;
import tools.jackson.databind.json.JsonMapper;

/** Which sentences are understood without the model, and, as important, which are left to it. */
class QuickCommandsTest {

    private static final Capabilities CAPS = new Capabilities(true, null, true);

    private final QuickCommands quick = new QuickCommands(JsonMapper.builder().build());

    private static HomeChannel channel(String id, String label) {
        return new HomeChannel(id, label, CAPS, false, null, null, null, null, null, null);
    }

    private static HomeDevice bar(String id, String name, Long room) {
        return new HomeDevice(id, name, name, "yeelight", room, false, true, true, Instant.EPOCH, Instant.EPOCH,
                List.of(channel("main", "Main"), channel("background", "Background")));
    }

    private static SceneView scene(long id, String name) {
        return new SceneView(id, name, "custom", null, List.of());
    }

    private static final List<SceneView> SCENES = List.of(scene(1, "All off"), scene(2, "Movie"), scene(3, "Randeczka z Olcią"),
            scene(4, "Spiaca Olcia"));

    /** The home of the person who asked for this: one lamp with a front and a back light. l1 is Main, l2 Background. */
    private final HomeCatalog home = new HomeCatalog(List.of(bar("lamp", "Lampka nad monitorem", null)), List.of(), SCENES);

    private final HomeCatalog twoBars = new HomeCatalog(List.of(bar("one", "Desk bar", 1L), bar("two", "Shelf bar", 2L)),
            List.of(new Room(1, "Study", 0), new Room(2, "Lounge", 1)), SCENES);

    private String plan(String sentence) {
        return plan(sentence, home);
    }

    /** The tool call a sentence turns into, as text; "-" when it is left to the model. */
    private String plan(String sentence, HomeCatalog catalog) {
        return plan(sentence, catalog, null);
    }

    private String plan(String sentence, HomeCatalog catalog, Long roomId) {
        Optional<QuickCommands.Plan> plan = quick.plan(sentence, catalog, roomId);
        return plan.map(p -> p.call().name() + " " + p.call().input()).orElse("-");
    }

    private boolean polish(String sentence) {
        return quick.plan(sentence, home, null).orElseThrow().polish();
    }

    // ---- what it does take ----

    @Test
    void switchingOneLightOnOrOffInEnglish() {
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":false}]}", plan("Turn off the background light"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":true}]}", plan("Turn on the Background light."));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l1\",\"on\":true}]}", plan("switch on the main light"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false}]}", plan("Turn the main light off"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false}]}", plan("Please turn off the main light please"));
    }

    @Test
    void theWakeWordAtTheStartOfASentenceIsIgnored() {
        String off = "set_lights {\"changes\":[{\"light\":\"l2\",\"on\":false}]}";
        assertEquals(off, plan("Hey Jarvis, turn off the background light"));
        assertEquals(off, plan("Jarvis turn off the background light."));
        assertEquals(off, plan("Hej Jarvis, wyłącz tylne światło"));
        assertEquals("-", plan("Hey Jarvis"));
        assertEquals("-", plan("Jarvis, what's the weather?"));
    }

    @Test
    void switchingOneLightOnOrOffInPolishWithEndingsAndMissingAccents() {
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":true}]}", plan("Włącz tylne światło"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":true}]}", plan("Włącz tylną lampkę."));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":false}]}", plan("Wylacz lampke background"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false}]}", plan("Wyłącz główne światło"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false}]}", plan("Zgaś przednie światło, proszę"));
        assertTrue(polish("Włącz tylne światło"));
        assertFalse(polish("Turn on the background light"));
    }

    @Test
    void aDeviceNamedWithoutAChannelMeansAllItsChannels() {
        String off = "set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false},{\"light\":\"l2\",\"on\":false}]}";
        assertEquals(off, plan("Wyłącz Lampka nad monitorem."));
        assertEquals(off, plan("Wyłącz lampkę nad monitorem"));
        assertEquals(off.replace("false", "true"), plan("Turn on the monitor lamp"));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":false}]}", plan("Wyłącz lampkę nad monitorem tylne"));
    }

    @Test
    void allTheLights() {
        String off = "set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false},{\"light\":\"l2\",\"on\":false}]}";
        assertEquals(off, plan("Turn off all the lights."));
        assertEquals(off, plan("Wyłącz wszystkie światła."));
        assertEquals(off, plan("wyłącz wszystko"));
        assertEquals(off.replace("false", "true"), plan("Turn on all the lights"));
    }

    @Test
    void scenesByNameWithOrWithoutTheWordScene() {
        assertEquals("apply_scene {\"scene\":\"s2\"}", plan("Turn on the Movie scene."));
        assertEquals("apply_scene {\"scene\":\"s2\"}", plan("turn on movie"));
        assertEquals("apply_scene {\"scene\":\"s2\"}", plan("Apply Movie"));
        assertEquals("apply_scene {\"scene\":\"s3\"}", plan("Turn on Randeczka z Olcią scene."));
        assertEquals("apply_scene {\"scene\":\"s4\"}", plan("Włącz scenę Spiąca Olcia"));
        assertTrue(polish("Włącz scenę Spiąca Olcia"));
    }

    // ---- what it leaves to the model ----

    @Test
    void anythingBeyondOnAndOffIsLeftToTheModel() {
        assertEquals("-", plan("Turn on the Background and set the color to green."));
        assertEquals("-", plan("Turn on the background light at 50%"));
        assertEquals("-", plan("Włącz tylne światło na niebieski."));
        assertEquals("-", plan("Włącz tylną lampkę na maxa na kolor zielony i przednią na maxa jasność."));
        assertEquals("-", plan("Set the background light to 40"));
        assertEquals("-", plan("Turn off the background light and turn on the main light"));
        assertEquals("-", plan("Turn off the background light on the left"));
    }

    @Test
    void questionsRequestsAndNegationsAreLeftToTheModel() {
        assertEquals("-", plan("Could you turn off the background light?"));
        assertEquals("-", plan("Is the background light on?"));
        assertEquals("-", plan("Don't turn off the background light"));
        assertEquals("-", plan("Nie wyłączaj tylnego światła"));
        assertEquals("-", plan("What's the weather?"));
        assertEquals("-", plan("Hello."));
        assertEquals("-", plan(""));
        assertEquals("-", plan("Turn off"));
    }

    @Test
    void aNameItIsNotSureOfIsLeftToTheModel() {
        assertEquals("-", plan("Turn on the kitchen light"));
        assertEquals("-", plan("Trun on the Spiaca Olcia theme")); // a typo in the verb
        assertEquals("-", plan("Turn on the lamp")); // which one?
        assertEquals("-", plan("Turn on Olcia")); // two scenes have it
        assertEquals("-", plan("Turn on the bedroom scene"));
    }

    @Test
    void aSceneCannotBeSwitchedOffAndALightCannotBeAppliedLikeAScene() {
        assertEquals("-", plan("Turn off the Movie scene"));
        assertEquals("-", plan("Turn off Movie"));
        assertEquals("-", plan("Apply the background light"));
    }

    @Test
    void whenTwoLightsFitItAsksTheModelInsteadOfGuessing() {
        assertEquals("-", plan("Turn off the background light", twoBars));
        assertEquals("-", plan("Turn on the main light", twoBars));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":false}]}", plan("Turn off the desk background light", twoBars));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l3\",\"on\":true}]}", plan("Turn on the shelf main light", twoBars));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false},{\"light\":\"l2\",\"on\":false}]}",
                plan("Turn off the desk bar", twoBars));
    }

    @Test
    void inARoomAllTheLightsAreThatRoomsAndTheLightIsTheOneInIt() {
        String study = "set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false},{\"light\":\"l2\",\"on\":false}]}";
        String lounge = "set_lights {\"changes\":[{\"light\":\"l3\",\"on\":false},{\"light\":\"l4\",\"on\":false}]}";
        assertEquals(study, plan("Turn off all the lights", twoBars, 1L));
        assertEquals(lounge, plan("Wyłącz wszystkie światła", twoBars, 2L));
        assertEquals(lounge, plan("Turn off everything", twoBars, 2L));
        // beyond the room only when they say so
        String home = "set_lights {\"changes\":[{\"light\":\"l1\",\"on\":false},{\"light\":\"l2\",\"on\":false},"
                + "{\"light\":\"l3\",\"on\":false},{\"light\":\"l4\",\"on\":false}]}";
        assertEquals(home, plan("Turn off all the lights", twoBars, null));
        assertEquals(home, plan("Turn off everything everywhere", twoBars, 1L));
        assertEquals(home, plan("Turn off the whole house", twoBars, 1L));
        assertEquals(home, plan("Wyłącz wszystko wszędzie", twoBars, 2L));
        // a room without lights leaves it to the model, which can ask
        assertEquals("-", plan("Turn off all the lights", twoBars, 99L));
        // "the background light" is the one in this room
        assertEquals("set_lights {\"changes\":[{\"light\":\"l2\",\"on\":false}]}", plan("Turn off the background light", twoBars, 1L));
        assertEquals("set_lights {\"changes\":[{\"light\":\"l4\",\"on\":false}]}", plan("Turn off the background light", twoBars, 2L));
        // naming the other one still reaches it
        assertEquals("set_lights {\"changes\":[{\"light\":\"l4\",\"on\":false}]}", plan("Turn off the shelf background light", twoBars, 1L));
        // a room that has neither of them does not decide
        assertEquals("-", plan("Turn off the background light", twoBars, 99L));
    }

    @Test
    void aHomeWithoutLightsHasNothingToSwitch() {
        HomeCatalog empty = new HomeCatalog(List.of(), List.of(), List.of());
        assertEquals("-", plan("Turn off all the lights", empty));
    }

    // ---- matching words ----

    @Test
    void wordsMatchWhenOnlyTheEndingDiffers() {
        assertTrue(QuickCommands.same("tylne", "tylna"));
        assertTrue(QuickCommands.same("glowne", "glownego"));
        assertTrue(QuickCommands.same("back", "back"));
        assertFalse(QuickCommands.same("lampa1", "lampa2"));
        assertFalse(QuickCommands.same("kot", "kota")); // short words must be exact
        assertFalse(QuickCommands.same("main", "maintenance"));
        assertFalse(QuickCommands.same("movie", "music"));
    }

    @Test
    void wordsAreLowerCaseWithoutAccents() {
        assertEquals(List.of("zolta", "lampka", "50"), QuickCommands.words("Żółta Lampka, 50%!"));
    }
}
