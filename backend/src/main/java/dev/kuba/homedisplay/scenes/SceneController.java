package dev.kuba.homedisplay.scenes;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kuba.homedisplay.devices.ChannelChange;
import dev.kuba.homedisplay.scenes.SceneViews.ApplyResult;
import dev.kuba.homedisplay.scenes.SceneViews.SceneList;
import dev.kuba.homedisplay.scenes.SceneViews.SceneView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/scenes")
public class SceneController {

    private final SceneService scenes;

    public SceneController(SceneService scenes) {
        this.scenes = scenes;
    }

    public record ActionRequest(@NotBlank String deviceId, @NotBlank String channel, @NotNull ChannelChange settings) {

        SceneAction toAction() {
            return new SceneAction(deviceId, channel, settings);
        }
    }

    /** {@code actions} may be left out when only renaming (and must be for the built-in scene). */
    public record SceneRequest(@NotBlank @Size(max = 40) String name, List<@Valid @NotNull ActionRequest> actions) {

        List<SceneAction> toActions() {
            return actions == null ? List.of() : actions.stream().map(ActionRequest::toAction).toList();
        }
    }

    public record LightRequest(@NotBlank String deviceId, @NotBlank String channel) {
    }

    public record CaptureRequest(@NotBlank @Size(max = 40) String name, @NotNull List<@Valid @NotNull LightRequest> channels) {
    }

    public record OrderRequest(@NotNull List<@NotNull Long> sceneIds) {
    }

    @GetMapping
    public SceneList list() {
        return new SceneList(scenes.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SceneView create(@Valid @RequestBody SceneRequest request) {
        return scenes.create(request.name(), request.toActions());
    }

    @PostMapping("/capture")
    @ResponseStatus(HttpStatus.CREATED)
    public SceneView capture(@Valid @RequestBody CaptureRequest request) {
        return scenes.capture(request.name(),
                request.channels().stream().map(c -> new SceneService.LightRef(c.deviceId(), c.channel())).toList());
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(@Valid @RequestBody OrderRequest request) {
        scenes.reorder(request.sceneIds());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    public SceneView update(@PathVariable long id, @Valid @RequestBody SceneRequest request) {
        return scenes.update(id, request.name(), request.toActions());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        scenes.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/apply")
    public ApplyResult apply(@PathVariable long id) {
        return scenes.apply(id);
    }
}
