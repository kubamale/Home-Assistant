package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import jakarta.servlet.http.Cookie;

/**
 * Rooms can be open to everyone, visible to everyone but controlled only by some people, or private to some people. The
 * fake light bar sits in the living room (open) and the fake lamp in the bedroom, whose setting each test chooses.
 */
class RoomAccessTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private Cookie admin;
    private long anna;
    private long bob;
    private Cookie annaCookie;
    private Cookie bobCookie;
    private long bedroom;
    private long living;

    @BeforeEach
    void setUpHome() throws Exception {
        admin = loginAsUser();
        anna = createUser(admin, "Anna", "anna-password");
        bob = createUser(admin, "Bob", "bob-password");
        annaCookie = loginAs("Anna", "anna-password");
        bobCookie = loginAs("Bob", "bob-password");
        bedroom = createRoom("Bedroom");
        living = createRoom("Living room");
        assign("fake-lamp", bedroom);
        assign("fake-light-bar", living);
    }

    private long createRoom(String name) throws Exception {
        String response = mvc.perform(post("/api/rooms").cookie(admin).contentType(JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*?\"id\":(\\d+).*", "$1"));
    }

    private void assign(String device, long room) throws Exception {
        mvc.perform(patch("/api/devices/" + device).cookie(admin).contentType(JSON).content("{\"roomId\":" + room + "}"))
                .andExpect(status().isNoContent());
    }

    private void setAccess(long room, String access, Long... members) throws Exception {
        String list = java.util.Arrays.stream(members).map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(put("/api/rooms/" + room + "/access").cookie(admin).contentType(JSON)
                .content("{\"access\":\"" + access + "\",\"members\":[" + list + "]}")).andExpect(status().isNoContent());
    }

    private void power(Cookie who, String device, boolean on, int expected) throws Exception {
        mvc.perform(post("/api/devices/" + device + "/power").cookie(who).contentType(JSON).content("{\"on\":" + on + "}"))
                .andExpect(status().is(expected));
    }

    @Test
    void everyRoomIsOpenToEveryoneUntilTheAdminChangesIt() throws Exception {
        mvc.perform(get("/api/devices").cookie(bobCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.devices", hasSize(2)))
                .andExpect(jsonPath("$.devices[0].controllable", is(true)))
                .andExpect(jsonPath("$.devices[1].controllable", is(true)));
        mvc.perform(get("/api/rooms").cookie(bobCookie)).andExpect(jsonPath("$.rooms[0].access", is("open")));
        power(bobCookie, "fake-lamp", true, 200);
    }

    @Test
    void aPrivateRoomDoesNotExistForPeopleOutsideIt() throws Exception {
        setAccess(bedroom, "private", anna);

        mvc.perform(get("/api/devices").cookie(bobCookie)).andExpect(jsonPath("$.devices", hasSize(1)))
                .andExpect(jsonPath("$.devices[0].id", is("fake-light-bar")));
        mvc.perform(get("/api/rooms").cookie(bobCookie)).andExpect(jsonPath("$.rooms[*].name", contains("Living room")));
        power(bobCookie, "fake-lamp", true, 404); // not 403: he is not told it exists

        mvc.perform(get("/api/devices").cookie(annaCookie)).andExpect(jsonPath("$.devices", hasSize(2)));
        power(annaCookie, "fake-lamp", true, 200);
        mvc.perform(get("/api/devices").cookie(admin)).andExpect(jsonPath("$.devices", hasSize(2))); // the admin always can
        power(admin, "fake-lamp", false, 200);
    }

    @Test
    void aViewOnlyRoomShowsItsDevicesButOnlyItsPeopleCanChangeThem() throws Exception {
        setAccess(bedroom, "view", anna);

        mvc.perform(get("/api/devices").cookie(bobCookie)).andExpect(jsonPath("$.devices", hasSize(2)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].controllable", contains(false)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].controllable", contains(true)));
        mvc.perform(get("/api/rooms").cookie(bobCookie))
                .andExpect(jsonPath("$.rooms[?(@.name=='Bedroom')].canControl", contains(false)));
        power(bobCookie, "fake-lamp", true, 403);
        mvc.perform(post("/api/devices/fake-lamp/channels/main").cookie(bobCookie).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isForbidden());

        power(annaCookie, "fake-lamp", true, 200);
        power(bobCookie, "fake-light-bar", true, 200); // the open room still works for him
    }

    @Test
    void aDisplayCanDoWhatItsOwnerCanDoAndNothingMore() throws Exception {
        setAccess(bedroom, "view", anna);
        Cookie bobsDisplay = pairDisplay(admin, bob);
        Cookie annasDisplay = pairDisplay(admin, anna);

        power(bobsDisplay, "fake-lamp", true, 403);
        power(annasDisplay, "fake-lamp", true, 200);
        mvc.perform(get("/api/devices").cookie(bobsDisplay)).andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].controllable", contains(false)));
    }

    @Test
    void aSharedSceneOnlyChangesTheLightsThePersonMayControl() throws Exception {
        setAccess(bedroom, "view", anna);
        power(admin, "fake-lamp", true, 200);
        power(admin, "fake-light-bar", true, 200);
        long allOff = jdbc.sql("select id from scene where kind = 'all_off'").query(Long.class).single();

        mvc.perform(post("/api/scenes/" + allOff + "/apply").cookie(bobCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped", is(0))); // All off only lists what he may switch, so nothing is left out
        mvc.perform(get("/api/devices").cookie(admin))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].power", contains(true))) // the lamp in the bedroom stayed on
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].power", contains(false)));

        // a scene for everyone that names the lamp: the rest applies, the lamp is skipped and counted
        String shared = "{\"name\":\"Evening\",\"shared\":true,\"actions\":["
                + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":false}},"
                + "{\"deviceId\":\"fake-light-bar\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}";
        String created = mvc.perform(post("/api/scenes").cookie(admin).contentType(JSON).content(shared))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long evening = Long.parseLong(created.replaceAll(".*?\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/scenes/" + evening + "/apply").cookie(bobCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied", is(1)))
                .andExpect(jsonPath("$.skipped", is(1)));
        mvc.perform(get("/api/devices").cookie(admin))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].power", contains(true)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].power", contains(true)));
    }

    @Test
    void aSceneCanOnlyHoldLightsThePersonMayControlAndHiddenOnesAreKept() throws Exception {
        String mixed = "{\"name\":\"Mine\",\"actions\":["
                + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":true}},"
                + "{\"deviceId\":\"fake-light-bar\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}";
        String created = mvc.perform(post("/api/scenes").cookie(bobCookie).contentType(JSON).content(mixed))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long mine = Long.parseLong(created.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        setAccess(bedroom, "view", anna);
        mvc.perform(post("/api/scenes").cookie(bobCookie).contentType(JSON).content(mixed.replace("Mine", "Again")))
                .andExpect(status().isForbidden());

        setAccess(bedroom, "private", anna);
        mvc.perform(post("/api/scenes").cookie(bobCookie).contentType(JSON).content(mixed.replace("Mine", "Again")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/scenes").cookie(bobCookie))
                .andExpect(jsonPath("$.scenes[?(@.name=='Mine')].actions[*].deviceId", contains("fake-light-bar")));

        // he renames it; the lamp he cannot see stays in the scene
        mvc.perform(put("/api/scenes/" + mine).cookie(bobCookie).contentType(JSON).content("{\"name\":\"Mine too\",\"actions\":["
                + "{\"deviceId\":\"fake-light-bar\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}"))
                .andExpect(status().isOk());
        assertEquals(2, jdbc.sql("select count(*) from scene_action where scene_id = ?").param(mine).query(Integer.class).single());
    }

    @Test
    void aLightYouCannotControlCannotBeAFavourite() throws Exception {
        setAccess(bedroom, "view", anna);
        mvc.perform(put("/api/quick-access").cookie(bobCookie).contentType(JSON)
                .content("{\"items\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/quick-access").cookie(annaCookie).contentType(JSON)
                .content("{\"items\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}")).andExpect(status().isNoContent());
    }

    @Test
    void onlyTheAdminChangesWhoUsesARoomAndTheRequestIsChecked() throws Exception {
        mvc.perform(put("/api/rooms/" + bedroom + "/access").cookie(annaCookie).contentType(JSON)
                .content("{\"access\":\"private\",\"members\":[" + anna + "]}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/rooms/" + bedroom + "/access").cookie(admin).contentType(JSON)
                .content("{\"access\":\"secret\",\"members\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/rooms/" + bedroom + "/access").cookie(admin).contentType(JSON)
                .content("{\"access\":\"private\",\"members\":[9999]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/rooms/9999/access").cookie(admin).contentType(JSON)
                .content("{\"access\":\"private\",\"members\":[]}")).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheAdminSeesWhoIsInARoomAndRemovingAPersonRemovesThemFromIt() throws Exception {
        setAccess(bedroom, "private", anna, bob);
        mvc.perform(get("/api/rooms").cookie(admin))
                .andExpect(jsonPath("$.rooms[?(@.name=='Bedroom')].members[*]", contains((int) anna, (int) bob)));
        mvc.perform(get("/api/rooms").cookie(annaCookie))
                .andExpect(jsonPath("$.rooms[?(@.name=='Bedroom')].members[*]", empty()));

        mvc.perform(delete("/api/users/" + bob).cookie(admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/rooms").cookie(admin))
                .andExpect(jsonPath("$.rooms[?(@.name=='Bedroom')].members[*]", contains((int) anna)));
    }

    @Test
    void aPrivateRoomWithNobodyInItIsTheAdminsAlone() throws Exception {
        setAccess(bedroom, "private");
        mvc.perform(get("/api/devices").cookie(annaCookie)).andExpect(jsonPath("$.devices", hasSize(1)));
        power(admin, "fake-lamp", true, 200);
    }

    @Test
    void anOpenRoomForgetsItsPeople() throws Exception {
        setAccess(bedroom, "private", anna);
        setAccess(bedroom, "open", anna);
        mvc.perform(get("/api/rooms").cookie(admin))
                .andExpect(jsonPath("$.rooms[?(@.name=='Bedroom')].members[*]", empty()));
    }
}
