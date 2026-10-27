package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.kuba.homedisplay.devices.Range;
import jakarta.servlet.http.Cookie;

class ChannelTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String BAR = "/api/devices/fake-light-bar/channels/";

    @Autowired
    DeviceRepository deviceRepository;

    @Test
    void deviceListShowsChannelsWithCapabilitiesAndValues() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[*].id", contains("main", "background")))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].capabilities.color", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].capabilities.colorTemp.min", contains(2700)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].capabilities.color", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].on", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].on", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].power", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels", hasSize(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].capabilities.brightness", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].capabilities.colorTemp", contains(nullValue())));
    }

    @Test
    void channelValuesAreChangedIndependently() throws Exception {
        Cookie user = loginAsUser();

        mvc.perform(post(BAR + "background").cookie(user).contentType(JSON)
                .content("{\"on\":true,\"brightness\":40,\"hue\":120,\"saturation\":70}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[1].on", is(true)))
                .andExpect(jsonPath("$.channels[1].brightness", is(40)))
                .andExpect(jsonPath("$.channels[1].hue", is(120)))
                .andExpect(jsonPath("$.channels[1].colorMode", is("color")))
                .andExpect(jsonPath("$.channels[0].brightness", is(80)));

        mvc.perform(post(BAR + "main").cookie(user).contentType(JSON).content("{\"colorTemp\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].colorTemp", is(3000)))
                .andExpect(jsonPath("$.channels[0].colorMode", is("temperature")))
                .andExpect(jsonPath("$.channels[1].colorMode", is("color")));

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].brightness", contains(40)));
    }

    @Test
    void changesAreCheckedAgainstWhatTheChannelSupports() throws Exception {
        Cookie user = loginAsUser();
        for (String body : List.of(
                "{}",
                "{\"hue\":10,\"saturation\":10}", // the main light has no colour
                "{\"brightness\":0}",
                "{\"brightness\":101}",
                "{\"colorTemp\":1700}", // warmer than the main light goes
                "{\"colorTemp\":7000}")) {
            mvc.perform(post(BAR + "main").cookie(user).contentType(JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String body : List.of(
                "{\"hue\":200}",
                "{\"saturation\":50}",
                "{\"hue\":360,\"saturation\":50}",
                "{\"hue\":10,\"saturation\":101}")) {
            mvc.perform(post(BAR + "background").cookie(user).contentType(JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/devices/fake-lamp/channels/main").cookie(user).contentType(JSON).content("{\"colorTemp\":3000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BAR + "front").cookie(user).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/devices/nope/channels/main").cookie(user).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isNotFound());

        // none of the rejected requests changed anything
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].brightness", contains(80)));
    }

    @Test
    void devicePowerSwitchesEveryChannel() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(post("/api/devices/fake-light-bar/power").cookie(user).contentType(JSON).content("{\"on\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.power", is(false)))
                .andExpect(jsonPath("$.channels[0].on", is(false)))
                .andExpect(jsonPath("$.channels[1].on", is(false)));
        mvc.perform(post("/api/devices/fake-light-bar/power").cookie(user).contentType(JSON).content("{\"on\":true}"))
                .andExpect(jsonPath("$.channels[0].on", is(true)))
                .andExpect(jsonPath("$.channels[1].on", is(true)));
    }

    @Test
    void aChannelOffMakesTheDeviceOffOnlyWhenAllAreOff() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(post(BAR + "main").cookie(user).contentType(JSON).content("{\"on\":false}"))
                .andExpect(jsonPath("$.power", is(false)));
        mvc.perform(post(BAR + "background").cookie(user).contentType(JSON).content("{\"on\":true}"))
                .andExpect(jsonPath("$.power", is(true)));
    }

    @Test
    void theDisplayMayAdjustButMustSignIn() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);
        mvc.perform(post(BAR + "main").cookie(display).contentType(JSON).content("{\"brightness\":30}"))
                .andExpect(status().isOk());
        mvc.perform(post(BAR + "main").contentType(JSON).content("{\"brightness\":30}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void capabilitiesAreStoredSoControlsExistWhileTheDeviceIsOffline() throws Exception {
        Cookie user = loginAsUser();
        deviceRepository.upsert(new DiscoveredDevice("gone-bar", "Gone bar", "yeelight", List.of(
                new ChannelInfo("main", "Main", new Capabilities(true, new Range(2700, 6500), false)),
                new ChannelInfo("background", "Background", new Capabilities(true, null, true)))), clock.instant());

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='gone-bar')].present", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='gone-bar')].channels[*].id", contains("main", "background")))
                .andExpect(jsonPath("$.devices[?(@.id=='gone-bar')].channels[0].capabilities.colorTemp.max", contains(6500)))
                .andExpect(jsonPath("$.devices[?(@.id=='gone-bar')].channels[1].capabilities.color", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='gone-bar')].channels[1].on", contains(false)));
    }

    @Test
    void quickAccessPinsChannelsSoMainAndBackgroundAreTwoTiles() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content(
                "{\"items\":[{\"deviceId\":\"fake-light-bar\",\"channel\":\"background\"},"
                        + "{\"deviceId\":\"fake-light-bar\",\"channel\":\"main\"},"
                        + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}")).andExpect(status().isNoContent());

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[0].quickAccess", contains(2)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].quickAccess", contains(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(3)));

        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content(
                "{\"items\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"background\"}]}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content(
                "{\"items\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"},{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}"))
                .andExpect(status().isBadRequest());
        // a failed request keeps the slots
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(3)));
    }

    @Test
    void storedChannelsAreWrittenToTheDatabase() {
        int rows = jdbc.sql("select count(*) from device where id = 'fake-light-bar' and channels like '%background%'")
                .query(Integer.class).single();
        assertEquals(1, rows);
    }
}
