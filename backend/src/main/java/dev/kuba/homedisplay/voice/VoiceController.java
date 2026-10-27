package dev.kuba.homedisplay.voice;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kuba.homedisplay.security.CredentialPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceService voice;

    public VoiceController(VoiceService voice) {
        this.voice = voice;
    }

    public record CommandRequest(@NotBlank @Size(max = 500) String text, @Size(max = 64) String conversationId) {
    }

    public record Status(boolean available, String model) {
    }

    /** One sentence in, an answer and the actions taken out. Send the returned conversationId to continue. */
    @PostMapping("/command")
    public VoiceService.Reply command(@Valid @RequestBody CommandRequest request, Authentication authentication) {
        return voice.command(request.text(), (CredentialPrincipal) authentication.getPrincipal(), request.conversationId());
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(voice.available(), voice.modelName());
    }

    @GetMapping("/history")
    public List<VoiceTurnRepository.VoiceTurn> history() {
        return voice.history();
    }
}
