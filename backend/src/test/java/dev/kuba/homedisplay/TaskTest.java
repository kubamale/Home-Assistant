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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import dev.kuba.homedisplay.stream.SseHub;
import jakarta.servlet.http.Cookie;

class TaskTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    SseHub hub;

    private long create(Cookie user, String body) throws Exception {
        String response = mvc.perform(post("/api/tasks").cookie(user).contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void createListCompleteAndDelete() throws Exception {
        Cookie user = loginAsUser();
        long id = create(user, "{\"title\":\"  Buy milk \"}");

        mvc.perform(get("/api/tasks").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Buy milk")))
                .andExpect(jsonPath("$[0].done", is(false)));

        mvc.perform(patch("/api/tasks/" + id).cookie(user).contentType(JSON).content("{\"done\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done", is(true)));
        mvc.perform(patch("/api/tasks/" + id).cookie(user).contentType(JSON).content("{\"done\":false}"))
                .andExpect(jsonPath("$.done", is(false)));

        mvc.perform(delete("/api/tasks/" + id).cookie(user)).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks").cookie(user)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete("/api/tasks/" + id).cookie(user)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/tasks/" + id).cookie(user).contentType(JSON).content("{\"done\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsBlankTitles() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(post("/api/tasks").cookie(user).contentType(JSON).content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks").cookie(user).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void todaysListUsesTheSavedTimezone() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(put("/api/settings/location").cookie(user).contentType(JSON)
                .content("{\"name\":\"Kraków\",\"latitude\":50,\"longitude\":19,\"timezone\":\"Europe/Warsaw\"}"));
        // 10:00 UTC on 24 Oct is 12:00 in Warsaw; the 25th is tomorrow
        create(user, "{\"title\":\"undated\"}");
        create(user, "{\"title\":\"due today\",\"dueDate\":\"2026-10-24\"}");
        create(user, "{\"title\":\"overdue\",\"dueDate\":\"2026-10-20\"}");
        create(user, "{\"title\":\"tomorrow\",\"dueDate\":\"2026-10-25\"}");

        mvc.perform(get("/api/tasks").cookie(user))
                .andExpect(jsonPath("$[*].title", contains("overdue", "due today", "undated")))
                .andExpect(jsonPath("$[0].overdue", is(true)))
                .andExpect(jsonPath("$[1].overdue", is(false)));
        mvc.perform(get("/api/tasks").cookie(user).param("scope", "all")).andExpect(jsonPath("$", hasSize(4)));

        // at 23:30 UTC it is already the 25th in Warsaw: "tomorrow" is due today, the old ones are overdue
        clock.set(java.time.Instant.parse("2026-10-24T23:30:00Z"));
        mvc.perform(get("/api/tasks").cookie(user)).andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void completedTasksStayForTheRestOfTheDayThenDisappear() throws Exception {
        Cookie user = loginAsUser();
        long id = create(user, "{\"title\":\"call mum\"}");
        mvc.perform(patch("/api/tasks/" + id).cookie(user).contentType(JSON).content("{\"done\":true}"));

        mvc.perform(get("/api/tasks").cookie(user)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].done", is(true)));

        clock.set(START.plusSeconds(24 * 3600));
        mvc.perform(get("/api/tasks").cookie(user)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void dueDatesCanBeSetAndCleared() throws Exception {
        Cookie user = loginAsUser();
        long id = create(user, "{\"title\":\"x\",\"dueDate\":\"2026-10-24\"}");
        mvc.perform(patch("/api/tasks/" + id).cookie(user).contentType(JSON).content("{\"clearDueDate\":true}"))
                .andExpect(jsonPath("$.dueDate").doesNotExist());
        mvc.perform(patch("/api/tasks/" + id).cookie(user).contentType(JSON).content("{\"dueDate\":\"2026-10-30\"}"))
                .andExpect(jsonPath("$.dueDate", is("2026-10-30")));
    }

    @Test
    void displayCanTickButNotEdit() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);
        long id = create(user, "{\"title\":\"x\"}");

        mvc.perform(patch("/api/tasks/" + id).cookie(display).contentType(JSON).content("{\"done\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done", is(true)));
        mvc.perform(patch("/api/tasks/" + id).cookie(display).contentType(JSON).content("{\"title\":\"hacked\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/tasks/" + id).cookie(display).contentType(JSON)
                .content("{\"done\":false,\"clearDueDate\":true}")).andExpect(status().isForbidden());
    }
}
