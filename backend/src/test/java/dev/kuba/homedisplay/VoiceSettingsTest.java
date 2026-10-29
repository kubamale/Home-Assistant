package dev.kuba.homedisplay;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

import dev.kuba.homedisplay.voice.SpeechException;
import jakarta.servlet.http.Cookie;

/** How the assistant sounds: the settings, the list of voices and spoken samples. */
@Import(VoiceSettingsTest.Config.class)
class VoiceSettingsTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedSpeech settingsSpeech() {
            return new ScriptedSpeech();
        }
    }

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    ScriptedSpeech speech;

    @BeforeEach
    void resetSpeech() {
        speech.reset();
        jdbc.sql("delete from setting where key = 'voice.settings'").update();
    }

    @Test
    void thereAreSensibleDefaultsUntilSomethingIsSaved() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/voice/settings").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.voice", is("Kore")))
                .andExpect(jsonPath("$.languages", hasItem("pl-PL")))
                .andExpect(jsonPath("$.languages", hasItem("en-US")))
                .andExpect(jsonPath("$.speakReplies", is(true)))
                .andExpect(jsonPath("$.wakeSensitivity", is("normal")))
                .andExpect(jsonPath("$.wakeReply", is("Yes?")));
    }

    @Test
    void whatIsSaidAfterTheWakeWordCanBeChangedOrSwitchedOff() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true,\"wakeReply\":\"  Tak?  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wakeReply", is("Tak?")));
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true,\"wakeReply\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wakeReply", is("")));
        mvc.perform(get("/api/voice/settings").cookie(user)).andExpect(jsonPath("$.wakeReply", is("")));
        // an older page does not send it: the default
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wakeReply", is("Yes?")));
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true,\"wakeReply\":\"" + "x".repeat(41) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theWakeWordSensitivityIsSavedAndSettingsWithoutItAreNormal() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true,\"wakeSensitivity\":\"high\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wakeSensitivity", is("high")));
        mvc.perform(get("/api/voice/settings").cookie(display)).andExpect(jsonPath("$.wakeSensitivity", is("high")));

        // an older page (or settings saved before there was one) does not send it
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wakeSensitivity", is("normal")));
        jdbc.sql("update setting set value = ? where key = 'voice.settings'")
                .param("{\"voice\":\"Puck\",\"style\":\"\",\"languages\":[\"pl-PL\"],\"speakReplies\":true}").update();
        mvc.perform(get("/api/voice/settings").cookie(user)).andExpect(jsonPath("$.voice", is("Puck")))
                .andExpect(jsonPath("$.wakeSensitivity", is("normal")));
    }

    @Test
    void settingsAreSavedAndTheDoorScreenCanReadThem() throws Exception {
        Cookie user = loginAsUser();
        Cookie display = pairDisplay(user);
        mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON)
                .content("{\"voice\":\"Puck\",\"style\":\" calm and friendly \",\"languages\":[\"pl-PL\"],\"speakReplies\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.style", is("calm and friendly")));

        mvc.perform(get("/api/voice/settings").cookie(display)).andExpect(status().isOk())
                .andExpect(jsonPath("$.voice", is("Puck")))
                .andExpect(jsonPath("$.languages", hasSize(1)))
                .andExpect(jsonPath("$.speakReplies", is(false)));
        mvc.perform(put("/api/voice/settings").cookie(display).contentType(JSON)
                .content("{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true}")).andExpect(status().isForbidden());
    }

    @Test
    void badSettingsAreRefused() throws Exception {
        Cookie user = loginAsUser();
        for (String body : new String[] {
                "{\"voice\":\"Nobody\",\"style\":\"\",\"languages\":[],\"speakReplies\":true}",
                "{\"voice\":\"Kore\",\"style\":\"" + "x".repeat(201) + "\",\"languages\":[],\"speakReplies\":true}",
                "{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[\"polish\"],\"speakReplies\":true}",
                "{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[],\"speakReplies\":true,\"wakeSensitivity\":\"extreme\"}",
                "{\"voice\":\"Kore\",\"style\":\"\",\"languages\":[\"pl-PL\",\"en-US\",\"de-DE\",\"fr-FR\",\"it-IT\"],\"speakReplies\":true}" }) {
            mvc.perform(put("/api/voice/settings").cookie(user).contentType(JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theVoicesAreListed() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(get("/api/voice/voices").cookie(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.voices", hasSize(30)))
                .andExpect(jsonPath("$.voices", hasItem("Sulafat")))
                .andExpect(jsonPath("$.defaultVoice", is("Kore")));
    }

    @Test
    void aSampleIsAWavFileSpokenWithTheChosenVoice() throws Exception {
        Cookie user = loginAsUser();
        byte[] wav = mvc.perform(post("/api/voice/sample").cookie(user).contentType(JSON).content("{\"voice\":\"Fenrir\",\"style\":\"warm\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(44 + ScriptedSpeech.PIECES * 1000, wav.length);
        assertEquals("Fenrir", speech.spoken.get(0).voice());
        assertEquals("warm", speech.spoken.get(0).style());
        assertTrue(speech.spoken.get(0).text().contains("Cześć"), "the sample should be heard in both languages");
    }

    @Test
    void sampleProblemsAreExplained() throws Exception {
        Cookie user = loginAsUser();
        mvc.perform(post("/api/voice/sample").cookie(user).contentType(JSON).content("{\"voice\":\"Nobody\"}")).andExpect(status().isBadRequest());

        speech.speakFailure = new SpeechException("The speech service is busy, try again in a moment");
        mvc.perform(post("/api/voice/sample").cookie(user).contentType(JSON).content("{\"voice\":\"Kore\"}")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail", containsString("busy")));

        speech.available = false;
        mvc.perform(post("/api/voice/sample").cookie(user).contentType(JSON).content("{\"voice\":\"Kore\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail", containsString("GEMINI_API_KEY")));
        mvc.perform(get("/api/voice/status").cookie(user)).andExpect(jsonPath("$.speaking", is(false)))
                .andExpect(jsonPath("$.listening", is(false)));
    }

    @Test
    void theSampleIsForTheUserOnly() throws Exception {
        Cookie display = pairDisplay(loginAsUser());
        mvc.perform(post("/api/voice/sample").cookie(display).contentType(JSON).content("{\"voice\":\"Kore\"}")).andExpect(status().isForbidden());
    }
}
