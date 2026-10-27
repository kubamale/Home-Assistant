package dev.kuba.homedisplay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import dev.kuba.homedisplay.stream.SseHub;
import jakarta.servlet.http.Cookie;

class StreamTest extends IntegrationTest {

    @Autowired
    SseHub hub;

    @Test
    void streamNeedsACredentialAndReceivesChangeEvents() throws Exception {
        mvc.perform(get("/api/stream")).andExpect(status().isUnauthorized());

        Cookie user = loginAsUser();
        MvcResult result = mvc.perform(get("/api/stream").cookie(user).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(post("/api/tasks").cookie(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")).andExpect(status().isCreated());

        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:ping"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:tasks.changed"), body);
        org.junit.jupiter.api.Assertions.assertTrue(hub.clientCount() >= 1);
    }
}
