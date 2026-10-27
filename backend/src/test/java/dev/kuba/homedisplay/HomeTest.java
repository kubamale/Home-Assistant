package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;

import dev.kuba.homedisplay.devices.DeviceInventory;
import dev.kuba.homedisplay.devices.DeviceRepository;
import dev.kuba.homedisplay.devices.DevicesDiscoveredEvent;
import dev.kuba.homedisplay.devices.DiscoveredDevice;
import dev.kuba.homedisplay.stream.ChangePublisher;
import jakarta.servlet.http.Cookie;

class HomeTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    DeviceRepository deviceRepository;

    /** The request body that pins the main channel of each device, in order. */
    private static String pins(String... deviceIds) {
        return java.util.Arrays.stream(deviceIds).map(id -> "{\"deviceId\":\"" + id + "\",\"channel\":\"main\"}")
                .collect(java.util.stream.Collectors.joining(",", "{\"items\":[", "]}"));
    }

    private long createRoom(Cookie user, String name) throws Exception {
        String body = mvc.perform(post("/api/rooms").cookie(user).contentType(JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void addStoredDevices(int count) {
        for (int i = 1; i <= count; i++) {
            deviceRepository.upsert(new DiscoveredDevice("extra-" + i, "Extra " + i, "test"), clock.instant());
        }
    }

    @Test
    void discoveredDevicesAreStoredUnassigned() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/devices").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.devices", hasSize(2)))
                .andExpect(jsonPath("$.devices[0].roomId", is(nullValue())))
                .andExpect(jsonPath("$.devices[0].channels[0].quickAccess", is(nullValue())))
                .andExpect(jsonPath("$.devices[0].present", is(true)));
    }

    @Test
    void inventoryAnnouncesOnlyRealChanges() {
        List<String> published = new ArrayList<>();
        ChangePublisher recorder = published::add;
        DeviceInventory inventory = new DeviceInventory(deviceRepository, recorder, clock);

        inventory.onDevicesDiscovered(new DevicesDiscoveredEvent(List.of(new DiscoveredDevice("new-1", "New bulb", "yeelight"))));
        assertEquals(List.of(ChangePublisher.HOME), published);

        published.clear();
        inventory.onDevicesDiscovered(new DevicesDiscoveredEvent(List.of(new DiscoveredDevice("new-1", "New bulb", "yeelight"))));
        assertEquals(List.of(), published, "the same report again changes nothing");

        inventory.onDevicesDiscovered(new DevicesDiscoveredEvent(List.of(new DiscoveredDevice("new-1", "Renamed on the bulb", "yeelight"))));
        assertEquals(List.of(ChangePublisher.HOME), published);
    }

    @Test
    void rediscoveryKeepsRoomAndOwnName() throws Exception {
        Cookie user = loginAsUser();
        long room = createRoom(user, "Kitchen");
        mvc.perform(patch("/api/devices/fake-lamp").cookie(user).contentType(JSON)
                .content("{\"name\":\"Reading lamp\",\"roomId\":" + room + "}")).andExpect(status().isNoContent());

        events.publishEvent(new DevicesDiscoveredEvent(List.of(new DiscoveredDevice("fake-lamp", "Desk lamp (fake)", "fake"))));

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].name", contains("Reading lamp")))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].reportedName", contains("Desk lamp (fake)")))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].roomId", contains((int) room)));
    }

    @Test
    void storedDeviceTheAgentNoLongerListsStaysAsUnavailable() throws Exception {
        Cookie user = loginAsUser();
        addStoredDevices(1);
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-1')].present", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-1')].reachable", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-1')].updatedAt", contains(nullValue())));
    }

    @Test
    void roomsAreCreatedRenamedReorderedAndDeleted() throws Exception {
        Cookie user = loginAsUser();
        long kitchen = createRoom(user, "Kitchen");
        long hall = createRoom(user, "Hall");

        mvc.perform(post("/api/rooms").cookie(user).contentType(JSON).content("{\"name\":\"kitchen\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/rooms").cookie(user).contentType(JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/rooms/" + hall).cookie(user).contentType(JSON).content("{\"name\":\"Hallway\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("Hallway")));
        mvc.perform(patch("/api/rooms/999").cookie(user).contentType(JSON).content("{\"name\":\"Ghost\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/rooms").cookie(user)).andExpect(jsonPath("$.rooms[*].name", contains("Kitchen", "Hallway")));

        mvc.perform(put("/api/rooms/order").cookie(user).contentType(JSON)
                .content("{\"roomIds\":[" + hall + "," + kitchen + "]}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/rooms").cookie(user)).andExpect(jsonPath("$.rooms[*].name", contains("Hallway", "Kitchen")));
        mvc.perform(put("/api/rooms/order").cookie(user).contentType(JSON).content("{\"roomIds\":[" + hall + "]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/rooms/" + hall).cookie(user)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/rooms/" + hall).cookie(user)).andExpect(status().isNotFound());
    }

    @Test
    void deletingARoomLeavesItsDevicesUnassigned() throws Exception {
        Cookie user = loginAsUser();
        long room = createRoom(user, "Office");
        mvc.perform(patch("/api/devices/fake-lamp").cookie(user).contentType(JSON).content("{\"roomId\":" + room + "}"))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/rooms/" + room).cookie(user)).andExpect(status().isNoContent());

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices", hasSize(2)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].roomId", contains(nullValue())));
    }

    @Test
    void devicesAreAssignedRenamedAndUnassigned() throws Exception {
        Cookie user = loginAsUser();
        long room = createRoom(user, "Living room");

        mvc.perform(patch("/api/devices/fake-lamp").cookie(user).contentType(JSON)
                .content("{\"name\":\"  Corner lamp \",\"roomId\":" + room + "}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].name", contains("Corner lamp")))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].roomId", contains((int) room)));

        // A blank name goes back to the reported one; the room is kept when it is not mentioned.
        mvc.perform(patch("/api/devices/fake-lamp").cookie(user).contentType(JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].name", contains("Desk lamp (fake)")))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].roomId", contains((int) room)));

        mvc.perform(patch("/api/devices/fake-lamp").cookie(user).contentType(JSON).content("{\"clearRoom\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].roomId", contains(nullValue())));

        mvc.perform(patch("/api/devices/fake-lamp").cookie(user).contentType(JSON).content("{\"roomId\":999}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/devices/nope").cookie(user).contentType(JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void quickAccessKeepsOrderAndEnforcesItsRules() throws Exception {
        Cookie user = loginAsUser();
        addStoredDevices(3);

        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON)
                .content(pins("extra-2", "fake-lamp", "extra-1"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-2')].channels[0].quickAccess", contains(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(2)))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-1')].channels[0].quickAccess", contains(3)))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-3')].channels[0].quickAccess", contains(nullValue())));

        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON)
                .content(pins("extra-1", "extra-2", "extra-3", "fake-lamp", "fake-light-bar")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON)
                .content(pins("extra-1", "extra-1"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON)
                .content(pins("nope"))).andExpect(status().isNotFound());

        // A rejected request leaves the previous slots alone.
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-2')].channels[0].quickAccess", contains(1)));

        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON).content(pins()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices[?(@.channels[0].quickAccess != null)]", empty()));
    }

    @Test
    void forgettingADeviceFreesAndClosesItsQuickAccessSlot() throws Exception {
        Cookie user = loginAsUser();
        addStoredDevices(2);
        mvc.perform(put("/api/quick-access").cookie(user).contentType(JSON)
                .content(pins("extra-1", "extra-2", "fake-lamp"))).andExpect(status().isNoContent());

        mvc.perform(delete("/api/devices/extra-1").cookie(user)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/devices/extra-1").cookie(user)).andExpect(status().isNotFound());

        mvc.perform(get("/api/devices").cookie(user))
                .andExpect(jsonPath("$.devices", hasSize(3)))
                .andExpect(jsonPath("$.devices[?(@.id=='extra-2')].channels[0].quickAccess", contains(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(2)));
    }

    @Test
    void theDisplayCanReadAndToggleButNotOrganise() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);

        mvc.perform(get("/api/rooms").cookie(display)).andExpect(status().isOk());
        mvc.perform(get("/api/devices").cookie(display)).andExpect(status().isOk());
        mvc.perform(post("/api/devices/fake-lamp/power").cookie(display).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/rooms").cookie(display).contentType(JSON).content("{\"name\":\"Hall\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/devices/fake-lamp").cookie(display).contentType(JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/devices/fake-lamp").cookie(display)).andExpect(status().isForbidden());
        mvc.perform(put("/api/quick-access").cookie(display).contentType(JSON).content(pins()))
                .andExpect(status().isForbidden());
    }

    @Test
    void organisingRequiresSignIn() throws Exception {
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/quick-access").contentType(JSON).content(pins()))
                .andExpect(status().isUnauthorized());
    }
}
