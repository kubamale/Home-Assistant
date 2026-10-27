package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import jakarta.servlet.http.Cookie;

class AuthTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Test
    void healthIsPublicButEverythingElseNeedsACredential() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("up")));
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/weather")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedWrongPasswordsAreRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"password\":\"nope\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"password\":\"testpass\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void loginSetsAnHttpOnlyCookieThatGrantsAccess() throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType(JSON).content("{\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("user")))
                .andReturn().getResponse();
        Cookie cookie = cookieFrom(response);
        org.junit.jupiter.api.Assertions.assertTrue(cookie.isHttpOnly());
        org.junit.jupiter.api.Assertions.assertTrue(response.getHeader("Set-Cookie").contains("SameSite=Lax"));

        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role", is("user")));
    }

    @Test
    void logoutRevokesTheToken() throws Exception {
        Cookie cookie = loginAsUser();
        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void crossOriginWritesAreRejected() throws Exception {
        Cookie cookie = loginAsUser();
        mvc.perform(post("/api/tasks").cookie(cookie).header("Origin", "https://evil.example")
                .header("Host", "home.example.com").contentType(JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks").cookie(cookie).header("Origin", "https://home.example.com")
                .header("Host", "home.example.com").contentType(JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void displayPairingGivesALimitedToken() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);

        mvc.perform(get("/api/auth/me").cookie(display)).andExpect(jsonPath("$.role", is("display")));
        mvc.perform(get("/api/tasks").cookie(display)).andExpect(status().isOk());
        mvc.perform(get("/api/devices").cookie(display)).andExpect(status().isOk());

        // the display cannot create or delete tasks, change settings, or manage credentials
        mvc.perform(post("/api/tasks").cookie(display).contentType(JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/tasks/1").cookie(display)).andExpect(status().isForbidden());
        mvc.perform(get("/api/credentials").cookie(display)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/pair/approve").cookie(display).contentType(JSON)
                .content("{\"userCode\":\"AAAA-AAAA\"}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings/location")
                .cookie(display).contentType(JSON)
                .content("{\"name\":\"X\",\"latitude\":1,\"longitude\":1,\"timezone\":\"UTC\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pairingTokenIsHandedOverOnlyOnceAndOnlyToTheDisplay() throws Exception {
        Cookie user = loginAsUser();
        String start = mvc.perform(post("/api/auth/pair/start")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String deviceCode = start.replaceAll(".*\"deviceCode\":\"([^\"]+)\".*", "$1");
        String userCode = start.replaceAll(".*\"userCode\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/auth/pair/poll").param("deviceCode", deviceCode)).andExpect(status().isAccepted());
        // knowing the short code is not enough to collect the token
        mvc.perform(get("/api/auth/pair/poll").param("deviceCode", userCode)).andExpect(status().isGone());

        // approving with the dash omitted and in lower case also works
        String typed = userCode.replace("-", "").toLowerCase();
        mvc.perform(post("/api/auth/pair/approve").cookie(user).contentType(JSON)
                .content("{\"userCode\":\"" + typed + "\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/auth/pair/poll").param("deviceCode", deviceCode)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/pair/poll").param("deviceCode", deviceCode)).andExpect(status().isGone());
    }

    @Test
    void unknownPairingCodeIsRejectedAndRateLimited() throws Exception {
        Cookie user = loginAsUser();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/pair/approve").cookie(user).contentType(JSON)
                    .content("{\"userCode\":\"ZZZZ-ZZZZ\"}")).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/auth/pair/approve").cookie(user).contentType(JSON)
                .content("{\"userCode\":\"ZZZZ-ZZZZ\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void pairingCodesExpire() throws Exception {
        String start = mvc.perform(post("/api/auth/pair/start")).andReturn().getResponse().getContentAsString();
        String deviceCode = start.replaceAll(".*\"deviceCode\":\"([^\"]+)\".*", "$1");
        clock.set(START.plusSeconds(11 * 60));
        mvc.perform(get("/api/auth/pair/poll").param("deviceCode", deviceCode)).andExpect(status().isGone());
    }

    @Test
    void revokingADisplayLocksItOut() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);
        var list = mvc.perform(get("/api/credentials").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andReturn().getResponse().getContentAsString();
        String id = list.replaceAll(".*\"id\":\"([^\"]+)\",\"kind\":\"display\".*", "$1");

        mvc.perform(delete("/api/credentials/" + id).cookie(user)).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks").cookie(display)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/credentials/" + id).cookie(user)).andExpect(status().isNotFound());
    }

    @Test
    void agentTokenIsShownOnceAndOnlyWorksForTheAgentChannel() throws Exception {
        Cookie user = loginAsUser();
        String body = mvc.perform(post("/api/credentials/agent").cookie(user).contentType(JSON)
                .content("{\"label\":\"Pi\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/tasks/1").header("Authorization", "Bearer " + token).contentType(JSON)
                .content("{\"done\":true}")).andExpect(status().isForbidden());
    }
}
