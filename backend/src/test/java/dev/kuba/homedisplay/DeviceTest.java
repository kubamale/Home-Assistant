package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import jakarta.servlet.http.Cookie;

class DeviceTest extends IntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Test
    void listsAndTogglesDevicesFromPhoneAndDisplay() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);

        mvc.perform(get("/api/devices").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.agentOnline", is(true)))
                .andExpect(jsonPath("$.devices", hasSize(2)));

        mvc.perform(post("/api/devices/fake-lamp/power").cookie(display).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.power", is(true)));
        mvc.perform(post("/api/devices/fake-lamp/power").cookie(user).contentType(JSON).content("{\"on\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.power", is(false)));
    }

    @Test
    void unknownDeviceIsNotFoundAndBodyIsValidated() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(post("/api/devices/nope/power").cookie(user).contentType(JSON).content("{\"on\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/devices/fake-lamp/power").cookie(user).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
