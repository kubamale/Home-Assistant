package dev.kuba.homedisplay.voice;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.security.CredentialPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    /** Two short sentences, one in each language the household uses, so a voice can be judged in both. */
    private static final String SAMPLE = "Hello, this is how I sound. Cześć, tak właśnie brzmię.";

    private final VoiceService voice;
    private final VoiceSettingsService settings;
    private final TextToSpeech speaker;
    private final SpeechToText listener;

    public VoiceController(VoiceService voice, VoiceSettingsService settings, TextToSpeech speaker, SpeechToText listener) {
        this.voice = voice;
        this.settings = settings;
        this.speaker = speaker;
        this.listener = listener;
    }

    public record SettingsRequest(@NotBlank String voice, @Size(max = 200) String style, @NotNull List<@NotBlank String> languages,
            boolean speakReplies, String wakeSensitivity, @Size(max = 200) String wakeReply) {
    }

    public record SampleRequest(@NotBlank String voice, @Size(max = 200) String style) {
    }

    public record Voices(List<String> voices, String defaultVoice) {
    }

    public record CommandRequest(@NotBlank @Size(max = 500) String text, @Size(max = 64) String conversationId) {
    }

    /** {@code available}: the typed assistant works. {@code listening} and {@code speaking}: the microphone and the speaker do. */
    public record Status(boolean available, String model, boolean listening, boolean speaking) {
    }

    /** One sentence in, an answer and the actions taken out. Send the returned conversationId to continue. */
    @PostMapping("/command")
    public VoiceService.Reply command(@Valid @RequestBody CommandRequest request, Authentication authentication) {
        return voice.command(request.text(), (CredentialPrincipal) authentication.getPrincipal(), request.conversationId());
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(voice.available(), voice.modelName(), listener.available(), speaker.available());
    }

    @GetMapping("/settings")
    public VoiceSettings settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public VoiceSettings saveSettings(@Valid @RequestBody SettingsRequest request) {
        return settings.save(new VoiceSettings(request.voice(), request.style(), request.languages(), request.speakReplies(),
                request.wakeSensitivity(), request.wakeReply()));
    }

    @GetMapping("/voices")
    public Voices voices() {
        return new Voices(GeminiVoices.NAMES, GeminiVoices.DEFAULT);
    }

    /** A spoken sample of a voice, as a WAV file, for the voice picker. */
    @PostMapping(value = "/sample", produces = "audio/wav")
    public ResponseEntity<byte[]> sample(@Valid @RequestBody SampleRequest request, Authentication authentication) {
        if (!GeminiVoices.NAMES.contains(request.voice())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown voice");
        }
        if (!speaker.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Voice is not set up yet: the server needs a GEMINI_API_KEY");
        }
        voice.checkLimit((CredentialPrincipal) authentication.getPrincipal());
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            speaker.speak(SAMPLE, request.voice(), request.style(), chunk -> {
                pcm.writeBytes(chunk);
                return true;
            });
        } catch (SpeechException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .body(Pcm.wav(pcm.toByteArray(), TextToSpeech.SAMPLE_RATE));
    }

    @GetMapping("/history")
    public List<VoiceTurnRepository.VoiceTurn> history() {
        return voice.history();
    }
}
