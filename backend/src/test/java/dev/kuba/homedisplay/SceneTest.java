package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import dev.kuba.homedisplay.devices.Capabilities;
import dev.kuba.homedisplay.devices.ChannelInfo;
import dev.kuba.homedisplay.devices.DeviceRepository;
import dev.kuba.homedisplay.devices.DiscoveredDevice;
import jakarta.servlet.http.Cookie;

class SceneTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String MOVIE = """
            {"name":"Movie","actions":[
              {"deviceId":"fake-light-bar","channel":"main","settings":{"on":false}},
              {"deviceId":"fake-light-bar","channel":"background","settings":{"brightness":20,"hue":220,"saturation":100}},
              {"deviceId":"fake-lamp","channel":"main","settings":{"on":false}}]}""";

    @Autowired
    DeviceRepository deviceRepository;

    private long create(Cookie user, String body) throws Exception {
        String response = mvc.perform(post("/api/scenes").cookie(user).contentType(JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*?\"id\":(\\d+).*", "$1"));
    }

    private void apply(Cookie user, long id) throws Exception {
        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(user)).andExpect(status().isOk());
    }

    private void addGhost() {
        deviceRepository.upsert(new DiscoveredDevice("ghost", "Unplugged lamp", "yeelight",
                List.of(new ChannelInfo("main", "Main", new Capabilities(true, null, false)))), clock.instant());
    }

    @Test
    void theBuiltInAllOffSceneExistsWithoutActions() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/scenes").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scenes", hasSize(1)))
                .andExpect(jsonPath("$.scenes[0].name", is("All off")))
                .andExpect(jsonPath("$.scenes[0].kind", is("all_off")))
                .andExpect(jsonPath("$.scenes[0].actions", empty()));
    }

    @Test
    void aSceneIsStoredWithDeviceNamesAndCapabilities() throws Exception {
        Cookie user = loginAsUser();
        create(user, MOVIE);
        mvc.perform(get("/api/scenes").cookie(user))
                .andExpect(jsonPath("$.scenes[1].name", is("Movie")))
                .andExpect(jsonPath("$.scenes[1].kind", is("custom")))
                .andExpect(jsonPath("$.scenes[1].actions", hasSize(3)))
                .andExpect(jsonPath("$.scenes[1].actions[1].deviceName", is("Light bar (fake)")))
                .andExpect(jsonPath("$.scenes[1].actions[1].channelLabel", is("Background")))
                .andExpect(jsonPath("$.scenes[1].actions[1].capabilities.color", is(true)))
                .andExpect(jsonPath("$.scenes[1].actions[1].settings.hue", is(220)))
                .andExpect(jsonPath("$.scenes[1].actions[1].settings.colorTemp", is(nullValue())));
    }

    @Test
    void scenesAreValidatedWhenSaved() throws Exception {
        Cookie user = loginAsUser();
        for (String bad : List.of(
                // no setting at all
                "{\"name\":\"A\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{}}]}",
                // the main light of the bar has no colour
                "{\"name\":\"A\",\"actions\":[{\"deviceId\":\"fake-light-bar\",\"channel\":\"main\",\"settings\":{\"hue\":10,\"saturation\":10}}]}",
                "{\"name\":\"A\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"brightness\":101}}]}",
                // the same light twice
                "{\"name\":\"A\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":true}},"
                        + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":false}}]}",
                "{\"name\":\"  \",\"actions\":[]}")) {
            mvc.perform(post("/api/scenes").cookie(user).contentType(JSON).content(bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/scenes").cookie(user).contentType(JSON)
                .content("{\"name\":\"A\",\"actions\":[{\"deviceId\":\"nope\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/scenes").cookie(user).contentType(JSON)
                .content("{\"name\":\"A\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"front\",\"settings\":{\"on\":true}}]}"))
                .andExpect(status().isNotFound());

        create(user, MOVIE);
        mvc.perform(post("/api/scenes").cookie(user).contentType(JSON).content("{\"name\":\"movie\",\"actions\":[]}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/scenes").cookie(user)).andExpect(jsonPath("$.scenes", hasSize(2)));
    }

    @Test
    void applyingASceneChangesEveryLightItNames() throws Exception {
        Cookie user = loginAsUser();
        long id = create(user, MOVIE);

        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied", is(3)))
                .andExpect(jsonPath("$.failed", empty()));

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].on", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].on", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].brightness", contains(20)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].hue", contains(220)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].colorMode", contains("color")));
    }

    @Test
    void aSettingLeftOutIsLeftAlone() throws Exception {
        Cookie user = loginAsUser();
        // colour only: the background keeps its brightness (50 in the fake bar) and, as it sets a colour, switches on
        long id = create(user, "{\"name\":\"Blue\",\"actions\":[{\"deviceId\":\"fake-light-bar\",\"channel\":\"background\","
                + "\"settings\":{\"hue\":240,\"saturation\":90}}]}");
        apply(user, id);
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].on", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].brightness", contains(50)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].hue", contains(240)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].brightness", contains(80)));
    }

    @Test
    void offWinsOverTheOtherSettings() throws Exception {
        Cookie user = loginAsUser();
        long id = create(user, "{\"name\":\"Off\",\"actions\":[{\"deviceId\":\"fake-light-bar\",\"channel\":\"main\","
                + "\"settings\":{\"on\":false,\"brightness\":10}}]}");
        apply(user, id);
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].on", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].brightness", contains(80)));
    }

    @Test
    void anUnavailableLightIsReportedAndTheRestStillApply() throws Exception {
        Cookie user = loginAsUser();
        addGhost();
        long id = create(user, "{\"name\":\"Mixed\",\"actions\":["
                + "{\"deviceId\":\"ghost\",\"channel\":\"main\",\"settings\":{\"on\":true}},"
                + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":true,\"brightness\":40}}]}");

        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied", is(1)))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].deviceName", is("Unplugged lamp")))
                .andExpect(jsonPath("$.failed[0].reason", is("Unavailable")));
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].brightness", contains(40)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].power", contains(true)));
    }

    @Test
    void allOffSwitchesOffEveryPresentLightAndIgnoresMissingOnes() throws Exception {
        Cookie user = loginAsUser();
        addGhost();
        mvc.perform(post("/api/devices/fake-lamp/power").cookie(user).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isOk());
        long allOff = Long.parseLong(mvc.perform(get("/api/scenes").cookie(user)).andReturn().getResponse().getContentAsString()
                .replaceAll(".*?\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/scenes/" + allOff + "/apply").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied", is(3))) // bar main + background, lamp
                .andExpect(jsonPath("$.failed", empty()));
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.power == true)]", empty()));
    }

    @Test
    void capturingSavesWhatTheLightsShowNow() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(post("/api/devices/fake-light-bar/channels/background").cookie(user).contentType(JSON)
                .content("{\"on\":true,\"brightness\":35,\"hue\":10,\"saturation\":60}")).andExpect(status().isOk());

        String body = mvc.perform(post("/api/scenes/capture").cookie(user).contentType(JSON).content("""
                {"name":"As it is","channels":[
                  {"deviceId":"fake-light-bar","channel":"main"},
                  {"deviceId":"fake-light-bar","channel":"background"},
                  {"deviceId":"fake-lamp","channel":"main"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actions", hasSize(3)))
                // main shows white at 4000 K, brightness 80
                .andExpect(jsonPath("$.actions[0].settings.on", is(true)))
                .andExpect(jsonPath("$.actions[0].settings.brightness", is(80)))
                .andExpect(jsonPath("$.actions[0].settings.colorTemp", is(4000)))
                .andExpect(jsonPath("$.actions[0].settings.hue", is(nullValue())))
                // background shows a colour
                .andExpect(jsonPath("$.actions[1].settings.hue", is(10)))
                .andExpect(jsonPath("$.actions[1].settings.saturation", is(60)))
                .andExpect(jsonPath("$.actions[1].settings.colorTemp", is(nullValue())))
                // the lamp is off, so only that is stored
                .andExpect(jsonPath("$.actions[2].settings.on", is(false)))
                .andExpect(jsonPath("$.actions[2].settings.brightness", is(nullValue())))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        // change things, then apply the scene: the lights come back
        mvc.perform(post("/api/devices/fake-light-bar/power").cookie(user).contentType(JSON).content("{\"on\":false}"));
        mvc.perform(post("/api/devices/fake-lamp/power").cookie(user).contentType(JSON).content("{\"on\":true}"));
        apply(user, id);
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].on", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].brightness", contains(35)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].on", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].power", contains(false)));

        mvc.perform(post("/api/scenes/capture").cookie(user).contentType(JSON).content("{\"name\":\"Empty\",\"channels\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/scenes/capture").cookie(user).contentType(JSON)
                .content("{\"name\":\"X\",\"channels\":[{\"deviceId\":\"nope\",\"channel\":\"main\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void scenesAreEditedRenamedReorderedAndDeleted() throws Exception {
        Cookie user = loginAsUser();
        long movie = create(user, MOVIE);
        long reading = create(user, "{\"name\":\"Reading\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\","
                + "\"settings\":{\"on\":true,\"brightness\":90}}]}");

        mvc.perform(put("/api/scenes/" + movie).cookie(user).contentType(JSON).content(
                "{\"name\":\"Cinema\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":false}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Cinema")))
                .andExpect(jsonPath("$.actions", hasSize(1)));
        mvc.perform(put("/api/scenes/" + movie).cookie(user).contentType(JSON).content("{\"name\":\"reading\",\"actions\":[]}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/scenes/999").cookie(user).contentType(JSON).content("{\"name\":\"Ghost\",\"actions\":[]}"))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/scenes/order").cookie(user).contentType(JSON)
                .content("{\"sceneIds\":[" + reading + "," + movie + "]}")).andExpect(status().isBadRequest()); // All off is missing
        mvc.perform(get("/api/scenes").cookie(user)).andExpect(jsonPath("$.scenes[*].name", contains("All off", "Cinema", "Reading")));

        mvc.perform(delete("/api/scenes/" + movie).cookie(user)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/scenes/" + movie).cookie(user)).andExpect(status().isNotFound());
        mvc.perform(get("/api/scenes").cookie(user)).andExpect(jsonPath("$.scenes[*].name", contains("All off", "Reading")));
    }

    @Test
    void theBuiltInSceneCanBeRenamedButNotEditedOrDeleted() throws Exception {
        Cookie user = loginAsUser();
        long allOff = Long.parseLong(mvc.perform(get("/api/scenes").cookie(user)).andReturn().getResponse().getContentAsString()
                .replaceAll(".*?\"id\":(\\d+).*", "$1"));
        mvc.perform(put("/api/scenes/" + allOff).cookie(user).contentType(JSON).content("{\"name\":\"Everything off\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("Everything off")));
        mvc.perform(put("/api/scenes/" + allOff).cookie(user).contentType(JSON).content(
                "{\"name\":\"X\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/scenes/" + allOff).cookie(user)).andExpect(status().isBadRequest());
    }

    @Test
    void scenesCanBePinnedNextToLightsAndFreeTheirSlotWhenDeleted() throws Exception {
        Cookie user = loginAsUser();
        long movie = create(user, MOVIE);
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content("""
                {"items":[{"deviceId":"fake-lamp","channel":"main"},{"sceneId":%d},{"deviceId":"fake-light-bar","channel":"main"}]}"""
                .formatted(movie))).andExpect(status().isNoContent());

        mvc.perform(get("/api/scenes").cookie(user)).andExpect(jsonPath("$.scenes[?(@.name=='Movie')].quickAccess", contains(2)));
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].quickAccess", contains(3)));

        for (String bad : List.of("{\"items\":[{\"sceneId\":%d,\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}".formatted(movie),
                "{\"items\":[{}]}")) {
            mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content(bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content("{\"items\":[{\"sceneId\":999}]}"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/scenes/" + movie).cookie(user)).andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].quickAccess", contains(2)));
    }

    @Test
    void forgettingADeviceRemovesItsActionsFromScenes() throws Exception {
        Cookie user = loginAsUser();
        addGhost();
        create(user, "{\"name\":\"Ghostly\",\"actions\":[{\"deviceId\":\"ghost\",\"channel\":\"main\",\"settings\":{\"on\":true}},"
                + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}");
        mvc.perform(delete("/api/devices/ghost").cookie(user)).andExpect(status().isNoContent());
        mvc.perform(get("/api/scenes").cookie(user))
                .andExpect(jsonPath("$.scenes[?(@.name=='Ghostly')].actions[*].deviceId", contains("fake-lamp")));
    }

    @Test
    void theDisplayCanReadAndApplyScenesButNotChangeThem() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);
        long id = create(user, MOVIE);

        mvc.perform(get("/api/scenes").cookie(display)).andExpect(status().isOk());
        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(display)).andExpect(status().isOk());
        mvc.perform(post("/api/scenes").cookie(display).contentType(JSON).content(MOVIE.replace("Movie", "Other")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/scenes/" + id).cookie(display).contentType(JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/scenes/" + id).cookie(display)).andExpect(status().isForbidden());
        mvc.perform(post("/api/scenes/capture").cookie(display).contentType(JSON).content("{\"name\":\"x\",\"channels\":[]}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/scenes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/scenes/" + id + "/apply")).andExpect(status().isUnauthorized());
    }
}
