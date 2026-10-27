package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import jakarta.servlet.http.Cookie;

/** Without an Anthropic API key the assistant says so and the rest of the app is untouched. */
class VoiceUnavailableTest extends IntegrationTest {

    @Test
    void withoutAnApiKeyTheAssistantExplainsItself() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/voice/status").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available", is(false)));
        mvc.perform(post("/api/voice/command").cookie(user).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("ANTHROPIC_API_KEY")));
        mvc.perform(get("/api/devices").cookie(user)).andExpect(status().isOk());
    }
}
