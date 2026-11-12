package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import jakarta.servlet.http.Cookie;

/** Accounts, and what belongs to a person: tasks, pins, scenes, calendar, and the look of their display. */
class UsersTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    /** The id of the first display in the person's credential list. */
    private String firstDisplayId(Cookie who) throws Exception {
        String list = mvc.perform(get("/api/credentials").cookie(who)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return list.replaceAll(".*?\\{\"id\":\"([^\"]+)\",\"kind\":\"display\".*", "$1");
    }

    // ---- sign-in ----

    @Test
    void theAdminSignsInWithOrWithoutANameAndOthersWithTheirOwnPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin", is(true)))
                .andExpect(jsonPath("$.name", is("Admin")));
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"name\":\"admin\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk());

        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"name\":\"anna\",\"password\":\"anna-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin", is(false)))
                .andExpect(jsonPath("$.name", is("Anna")));
    }

    @Test
    void aWrongNameOrPasswordIsRejectedTheSameWay() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"name\":\"Anna\",\"password\":\"testpass\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"name\":\"Nobody\",\"password\":\"anna-password\"}"))
                .andExpect(status().isUnauthorized());
        // the admin's password does not open someone else's account, and theirs does not open the admin's
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"password\":\"anna-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheAdminManagesPeople() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");

        mvc.perform(get("/api/users").cookie(annaCookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users").cookie(annaCookie).contentType(JSON)
                .content("{\"name\":\"Bob\",\"password\":\"bob-password\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/" + anna).cookie(annaCookie)).andExpect(status().isForbidden());

        mvc.perform(get("/api/users").cookie(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Admin", "Anna")));
    }

    @Test
    void namesAreUniqueAndPasswordsMustBeLongEnough() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        mvc.perform(post("/api/users").cookie(admin).contentType(JSON)
                .content("{\"name\":\"ANNA\",\"password\":\"another-password\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/users").cookie(admin).contentType(JSON)
                .content("{\"name\":\"Bob\",\"password\":\"short\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void aPersonChangesTheirOwnPasswordWithTheCurrentOneAndOtherLoginsEnd() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie phone = loginAs("Anna", "anna-password");
        Cookie tablet = loginAs("Anna", "anna-password");

        mvc.perform(put("/api/users/" + anna + "/password").cookie(phone).contentType(JSON)
                .content("{\"currentPassword\":\"wrong\",\"password\":\"a-new-password\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/users/" + anna + "/password").cookie(phone).contentType(JSON)
                .content("{\"currentPassword\":\"anna-password\",\"password\":\"a-new-password\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").cookie(phone)).andExpect(status().isOk()); // the one she changed it from stays
        mvc.perform(get("/api/auth/me").cookie(tablet)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"name\":\"Anna\",\"password\":\"anna-password\"}"))
                .andExpect(status().isUnauthorized());
        loginAs("Anna", "a-new-password");
    }

    @Test
    void aPersonCannotChangeSomeoneElsesPasswordAndTheAdminsIsNotStored() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        long bob = createUser(admin, "Bob", "bob-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        mvc.perform(put("/api/users/" + bob + "/password").cookie(annaCookie).contentType(JSON)
                .content("{\"currentPassword\":\"bob-password\",\"password\":\"hijacked-password\"}"))
                .andExpect(status().isForbidden());
        // the admin can reset a password without knowing the old one
        mvc.perform(put("/api/users/" + anna + "/password").cookie(admin).contentType(JSON)
                .content("{\"password\":\"reset-password\"}")).andExpect(status().isNoContent());
        loginAs("Anna", "reset-password");
        // but the admin's own is the one set on the server
        mvc.perform(put("/api/users/1/password").cookie(admin).contentType(JSON)
                .content("{\"password\":\"a-new-password\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void removingAPersonRemovesTheirThingsAndTheAdminCannotBeRemoved() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        mvc.perform(post("/api/tasks").cookie(annaCookie).contentType(JSON).content("{\"title\":\"Mine\"}"))
                .andExpect(status().isCreated());
        Cookie display = pairDisplay(admin, anna);

        mvc.perform(delete("/api/users/" + anna).cookie(admin)).andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").cookie(annaCookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(display)).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.sql("select count(*) from task where user_id = ?").param(anna).query(Integer.class).single());
        mvc.perform(delete("/api/users/1").cookie(admin)).andExpect(status().isBadRequest());
    }

    // ---- managing the home is the admin's ----

    @Test
    void onlyTheAdminManagesRoomsDevicesLocationAndTheAgent() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");

        mvc.perform(post("/api/rooms").cookie(anna).contentType(JSON).content("{\"name\":\"Hall\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rooms").cookie(admin).contentType(JSON).content("{\"name\":\"Hall\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/devices/fake-lamp").cookie(anna).contentType(JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/devices/fake-lamp").cookie(anna)).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/location").cookie(anna).contentType(JSON)
                .content("{\"name\":\"X\",\"latitude\":1,\"longitude\":1,\"timezone\":\"UTC\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/credentials/agent").cookie(anna).contentType(JSON).content("{\"label\":\"Pi\"}"))
                .andExpect(status().isForbidden());

        // but she reads the home and controls the lights
        mvc.perform(get("/api/rooms").cookie(anna)).andExpect(status().isOk());
        mvc.perform(get("/api/devices").cookie(anna)).andExpect(status().isOk());
        mvc.perform(post("/api/devices/fake-lamp/power").cookie(anna).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isOk());
    }

    // ---- tasks ----

    @Test
    void tasksBelongToOnePersonAndTheirDisplayShowsThem() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        mvc.perform(post("/api/tasks").cookie(admin).contentType(JSON).content("{\"title\":\"Admin's task\"}"))
                .andExpect(status().isCreated());
        String annaTask = mvc.perform(post("/api/tasks").cookie(annaCookie).contentType(JSON).content("{\"title\":\"Anna's task\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long annaTaskId = Long.parseLong(annaTask.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/tasks").cookie(admin)).andExpect(jsonPath("$[*].title", contains("Admin's task")));
        mvc.perform(get("/api/tasks").cookie(annaCookie)).andExpect(jsonPath("$[*].title", contains("Anna's task")));

        Cookie annasDisplay = pairDisplay(admin, anna); // the admin sets it up for her
        Cookie adminsDisplay = pairDisplay(admin);
        mvc.perform(get("/api/tasks").cookie(annasDisplay)).andExpect(jsonPath("$[*].title", contains("Anna's task")));
        mvc.perform(get("/api/tasks").cookie(adminsDisplay)).andExpect(jsonPath("$[*].title", contains("Admin's task")));

        // nobody reaches another person's task by its id
        mvc.perform(patch("/api/tasks/" + annaTaskId).cookie(admin).contentType(JSON).content("{\"done\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/tasks/" + annaTaskId).cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/tasks/" + annaTaskId).cookie(adminsDisplay).contentType(JSON).content("{\"done\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/tasks/" + annaTaskId).cookie(annasDisplay).contentType(JSON).content("{\"done\":true}"))
                .andExpect(status().isOk());
    }

    // ---- pairing a display to a person ----

    @Test
    void aDisplayBelongsToTheOneItWasApprovedFor() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annasDisplay = pairDisplay(admin, anna);

        mvc.perform(get("/api/auth/me").cookie(annasDisplay)).andExpect(jsonPath("$.role", is("display")))
                .andExpect(jsonPath("$.name", is("Anna"))).andExpect(jsonPath("$.admin", is(false)));
        mvc.perform(get("/api/display/config").cookie(annasDisplay)).andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.name", is("Anna")))
                .andExpect(jsonPath("$.layout.columns", hasSize(3)));
    }

    @Test
    void aDisplayOfTheAdminDoesNotManageTheHome() throws Exception {
        Cookie admin = loginAsUser();
        Cookie display = pairDisplay(admin);
        mvc.perform(get("/api/auth/me").cookie(display)).andExpect(jsonPath("$.admin", is(false)));
        mvc.perform(post("/api/rooms").cookie(display).contentType(JSON).content("{\"name\":\"Hall\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPersonCanOnlyApproveADisplayForThemselvesButTheAdminForAnyone() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");

        String start = mvc.perform(post("/api/auth/pair/start")).andReturn().getResponse().getContentAsString();
        String code = start.replaceAll(".*\"userCode\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/auth/pair/approve").cookie(annaCookie).contentType(JSON)
                .content("{\"userCode\":\"" + code + "\",\"userId\":1}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/pair/approve").cookie(admin).contentType(JSON)
                .content("{\"userCode\":\"" + code + "\",\"userId\":9999}")).andExpect(status().isNotFound());
        // left out, it is the person approving
        mvc.perform(post("/api/auth/pair/approve").cookie(annaCookie).contentType(JSON)
                .content("{\"userCode\":\"" + code + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is((int) anna))).andExpect(jsonPath("$.userName", is("Anna")));
    }

    @Test
    void peopleSeeAndRevokeTheirOwnDisplaysAndTheAdminSeesAll() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        pairDisplay(annaCookie);
        pairDisplay(admin);

        mvc.perform(get("/api/credentials").cookie(annaCookie)).andExpect(jsonPath("$[*].kind", contains("user", "display")));
        mvc.perform(get("/api/credentials").cookie(annaCookie)).andExpect(jsonPath("$[*].userName", contains("Anna", "Anna")));
        mvc.perform(get("/api/credentials").cookie(admin)).andExpect(jsonPath("$", hasSize(4)));

        String adminsDisplay = firstDisplayId(admin);
        // the first display in the admin's list is Anna's (older); the admin's own comes later, so find by owner
        String list = mvc.perform(get("/api/credentials").cookie(admin)).andReturn().getResponse().getContentAsString();
        String adminsOwn = list.replaceAll(".*\\{\"id\":\"([^\"]+)\",\"kind\":\"display\"[^}]*\"userName\":\"Admin\".*", "$1");
        mvc.perform(delete("/api/credentials/" + adminsOwn).cookie(annaCookie)).andExpect(status().isNotFound());
        mvc.perform(put("/api/credentials/" + adminsOwn + "/room").cookie(annaCookie).contentType(JSON).content("{\"roomId\":null}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/credentials/" + adminsDisplay).cookie(annaCookie)).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertNotEquals(adminsOwn, adminsDisplay);
    }

    @Test
    void theAdminHandsADisplayToAnotherPerson() throws Exception {
        Cookie admin = loginAsUser();
        long anna = createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        Cookie display = pairDisplay(admin);
        String id = firstDisplayId(admin);

        mvc.perform(put("/api/credentials/" + id + "/user").cookie(annaCookie).contentType(JSON).content("{\"userId\":" + anna + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/credentials/" + id + "/user").cookie(admin).contentType(JSON).content("{\"userId\":" + anna + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(display)).andExpect(jsonPath("$.name", is("Anna")));
    }

    // ---- the look of a display ----

    @Test
    void aPersonSetsTheLayoutOfTheirDisplayAndTheDisplayGetsIt() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        Cookie display = pairDisplay(annaCookie);
        String id = firstDisplayId(annaCookie);

        mvc.perform(get("/api/credentials/" + id + "/layout").cookie(annaCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].widgets[0].type", is("clock")));

        String layout = """
                {"columns":[
                  {"widgets":[{"type":"calendar","count":3},{"type":"tasks"}]},
                  {"widgets":[{"type":"clock","hour12":true},{"type":"quickAccess"}]}],
                 "voice":false,"motion":false}""";
        mvc.perform(put("/api/credentials/" + id + "/layout").cookie(annaCookie).contentType(JSON).content(layout))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(2)))
                .andExpect(jsonPath("$.motion", is(false)))
                .andExpect(jsonPath("$.columns[0].widgets[1].count", is(6))); // the missing setting is filled in

        mvc.perform(get("/api/display/config").cookie(display)).andExpect(status().isOk())
                .andExpect(jsonPath("$.layout.voice", is(false)))
                .andExpect(jsonPath("$.layout.motion", is(false)))
                .andExpect(jsonPath("$.layout.columns[0].widgets[0].type", is("calendar")))
                .andExpect(jsonPath("$.layout.columns[0].widgets[0].count", is(3)))
                .andExpect(jsonPath("$.layout.columns[1].widgets[0].hour12", is(true)));

        mvc.perform(delete("/api/credentials/" + id + "/layout").cookie(annaCookie)).andExpect(status().isOk());
        mvc.perform(get("/api/display/config").cookie(display)).andExpect(jsonPath("$.layout.columns", hasSize(3)))
                .andExpect(jsonPath("$.layout.voice", is(true)))
                .andExpect(jsonPath("$.layout.motion", is(true))); // a standard layout moves
    }

    @Test
    void nobodyEditsTheLayoutOfSomeoneElsesDisplayExceptTheAdmin() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        createUser(admin, "Bob", "bob-password");
        Cookie annaCookie = loginAs("Anna", "anna-password");
        Cookie bobCookie = loginAs("Bob", "bob-password");
        pairDisplay(annaCookie);
        String id = firstDisplayId(annaCookie);
        String layout = "{\"columns\":[{\"widgets\":[{\"type\":\"clock\"}]}],\"voice\":true}";

        mvc.perform(put("/api/credentials/" + id + "/layout").cookie(bobCookie).contentType(JSON).content(layout))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/credentials/" + id + "/layout").cookie(bobCookie)).andExpect(status().isNotFound());
        mvc.perform(put("/api/credentials/" + id + "/layout").cookie(admin).contentType(JSON).content(layout))
                .andExpect(status().isOk());
    }

    @Test
    void aBadLayoutIsRejected() throws Exception {
        Cookie admin = loginAsUser();
        pairDisplay(admin);
        String id = firstDisplayId(admin);
        String[] bad = {
                "{\"columns\":[],\"voice\":true}",
                "{\"columns\":[{\"widgets\":[{\"type\":\"nope\"}]}],\"voice\":true}",
                "{\"columns\":[{\"widgets\":[{\"type\":\"clock\"},{\"type\":\"clock\"}]}],\"voice\":true}",
                "{\"columns\":[{\"widgets\":[{\"type\":\"tasks\",\"count\":50}]}],\"voice\":true}",
                "{\"columns\":[{\"widgets\":[]}],\"voice\":true}",
                "{\"columns\":[{\"widgets\":[{\"type\":\"clock\"}]},{\"widgets\":[{\"type\":\"tasks\"}]},"
                        + "{\"widgets\":[{\"type\":\"weather\"}]},{\"widgets\":[{\"type\":\"calendar\"}]}],\"voice\":true}",
        };
        for (String layout : bad) {
            mvc.perform(put("/api/credentials/" + id + "/layout").cookie(admin).contentType(JSON).content(layout))
                    .andExpect(status().isBadRequest());
        }
        // a phone login has no layout
        String phone = mvc.perform(get("/api/credentials").cookie(admin)).andReturn().getResponse().getContentAsString()
                .replaceAll(".*?\\{\"id\":\"([^\"]+)\",\"kind\":\"user\".*", "$1");
        mvc.perform(get("/api/credentials/" + phone + "/layout").cookie(admin)).andExpect(status().isNotFound());
    }

    // ---- pins ----

    @Test
    void quickAccessIsPerPerson() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");
        mvc.perform(put("/api/quick-access").cookie(admin).contentType(JSON)
                .content("{\"items\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}")).andExpect(status().isNoContent());
        mvc.perform(put("/api/quick-access").cookie(anna).contentType(JSON)
                .content("{\"items\":[{\"deviceId\":\"fake-light-bar\",\"channel\":\"background\"},"
                        + "{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}")).andExpect(status().isNoContent());

        mvc.perform(get("/api/devices").cookie(admin))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(1)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].quickAccess", contains((Object) null)));
        mvc.perform(get("/api/devices").cookie(anna))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(2)))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].quickAccess", contains(1)));

        Cookie annasDisplay = pairDisplay(anna);
        mvc.perform(get("/api/devices").cookie(annasDisplay))
                .andExpect(jsonPath("$.devices[?(@.id=='fake-light-bar')].channels[1].quickAccess", contains(1)));
    }

    // ---- scenes ----

    @Test
    void aScenePersonalToOnePersonIsInvisibleToOthers() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");
        String created = mvc.perform(post("/api/scenes").cookie(anna).contentType(JSON)
                .content("{\"name\":\"Anna's reading\",\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":true}}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared", is(false)))
                .andExpect(jsonPath("$.editable", is(true)))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/scenes").cookie(anna)).andExpect(jsonPath("$.scenes[*].name", contains("All off", "Anna's reading")));
        mvc.perform(get("/api/scenes").cookie(admin)).andExpect(jsonPath("$.scenes[*].name", contains("All off")));
        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(put("/api/scenes/" + id).cookie(admin).contentType(JSON).content("{\"name\":\"Mine now\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/scenes/" + id).cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(put("/api/quick-access").cookie(admin).contentType(JSON).content("{\"items\":[{\"sceneId\":" + id + "}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(anna)).andExpect(status().isOk());
    }

    @Test
    void sharedScenesAreEveryonesButOnlyTheAdminChangesThem() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");
        String body = "{\"name\":\"Movie\",\"shared\":true,\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":false}}]}";

        mvc.perform(post("/api/scenes").cookie(anna).contentType(JSON).content(body)).andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/scenes").cookie(admin).contentType(JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.shared", is(true)))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/scenes").cookie(anna)).andExpect(jsonPath("$.scenes[1].name", is("Movie")))
                .andExpect(jsonPath("$.scenes[1].editable", is(false)));
        mvc.perform(post("/api/scenes/" + id + "/apply").cookie(anna)).andExpect(status().isOk());
        mvc.perform(delete("/api/scenes/" + id).cookie(anna)).andExpect(status().isForbidden());
        mvc.perform(put("/api/scenes/" + id).cookie(anna).contentType(JSON).content("{\"name\":\"Mine\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/scenes/" + builtInSceneId()).cookie(anna)).andExpect(status().isForbidden()); // the built-in one too
        // and she can pin it on her own display
        mvc.perform(put("/api/quick-access").cookie(anna).contentType(JSON).content("{\"items\":[{\"sceneId\":" + id + "}]}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingAScenePinnedByTwoPeopleKeepsEachOnesSlotsInOrder() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");
        String scene = "{\"name\":\"Movie\",\"shared\":true,\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":false}}]}";
        String created = mvc.perform(post("/api/scenes").cookie(admin).contentType(JSON).content(scene))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*?\"id\":(\\d+).*", "$1"));
        String pins = "{\"items\":[{\"sceneId\":" + id + "},{\"deviceId\":\"fake-lamp\",\"channel\":\"main\"}]}";
        mvc.perform(put("/api/quick-access").cookie(admin).contentType(JSON).content(pins)).andExpect(status().isNoContent());
        mvc.perform(put("/api/quick-access").cookie(anna).contentType(JSON).content(pins)).andExpect(status().isNoContent());

        mvc.perform(delete("/api/scenes/" + id).cookie(admin)).andExpect(status().isNoContent());

        for (Cookie who : new Cookie[] {admin, anna}) {
            mvc.perform(get("/api/devices").cookie(who))
                    .andExpect(jsonPath("$.devices[?(@.id=='fake-lamp')].channels[0].quickAccess", contains(1)));
        }
    }

    @Test
    void reorderingTouchesOnlyTheScenesThePersonCanEdit() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");
        String action = "\"actions\":[{\"deviceId\":\"fake-lamp\",\"channel\":\"main\",\"settings\":{\"on\":false}}]";
        long one = idOf(mvc.perform(post("/api/scenes").cookie(anna).contentType(JSON).content("{\"name\":\"One\"," + action + "}"))
                .andReturn().getResponse().getContentAsString());
        long two = idOf(mvc.perform(post("/api/scenes").cookie(anna).contentType(JSON).content("{\"name\":\"Two\"," + action + "}"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(put("/api/scenes/order").cookie(anna).contentType(JSON).content("{\"sceneIds\":[" + two + "," + one + "]}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/scenes").cookie(anna))
                .andExpect(jsonPath("$.scenes[*].name", contains("All off", "Two", "One")));
        // she cannot list the shared built-in scene among hers
        mvc.perform(put("/api/scenes/order").cookie(anna).contentType(JSON).content("{\"sceneIds\":[" + builtInSceneId() + "," + two + "," + one + "]}"))
                .andExpect(status().isBadRequest());
    }

    private long builtInSceneId() {
        return jdbc.sql("select id from scene where kind = 'all_off'").query(Long.class).single();
    }

    private static long idOf(String json) {
        return Long.parseLong(json.replaceAll(".*?\"id\":(\\d+).*", "$1"));
    }

    // ---- calendar ----

    @Test
    void thePersonalCalendarNeedsASecretKeyAndAReachableAccount() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");

        mvc.perform(get("/api/calendar/connection").cookie(anna)).andExpect(status().isOk())
                .andExpect(jsonPath("$.connected", is(false)));
        // the server must not be pointed at itself
        mvc.perform(put("/api/calendar/connection").cookie(anna).contentType(JSON)
                .content("{\"url\":\"https://localhost/\",\"username\":\"a@b.c\",\"password\":\"pw\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/calendar/connection").cookie(anna).contentType(JSON)
                .content("{\"url\":\"http://example.com/\",\"username\":\"a@b.c\",\"password\":\"pw\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/calendar/connection").cookie(anna)).andExpect(jsonPath("$.connected", is(false)));
    }

    @Test
    void everyonesCalendarFollowsThePersonAndTheSampleCalendarIsForAll() throws Exception {
        Cookie admin = loginAsUser();
        createUser(admin, "Anna", "anna-password");
        Cookie anna = loginAs("Anna", "anna-password");
        Cookie annasDisplay = pairDisplay(anna);
        // tests run with the sample calendar, which every login gets so that there is something to show
        mvc.perform(get("/api/calendar/today").cookie(annasDisplay)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured", is(true)));
        mvc.perform(get("/api/calendar/connection").cookie(anna)).andExpect(jsonPath("$.connected", is(false)))
                .andExpect(jsonPath("$.serverAccount", is(true)));
        mvc.perform(delete("/api/calendar/connection").cookie(anna)).andExpect(status().isNoContent());
    }
}
