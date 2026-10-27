package dev.kuba.homedisplay.rooms;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    public record RoomRequest(@NotBlank @Size(max = 40) String name) {
    }

    public record OrderRequest(@NotNull List<@NotNull Long> roomIds) {
    }

    public record RoomList(List<Room> rooms) {
    }

    @GetMapping
    public RoomList list() {
        return new RoomList(rooms.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Room create(@Valid @RequestBody RoomRequest request) {
        return rooms.create(request.name());
    }

    @PatchMapping("/{id}")
    public Room rename(@PathVariable long id, @Valid @RequestBody RoomRequest request) {
        return rooms.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        rooms.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(@Valid @RequestBody OrderRequest request) {
        rooms.reorder(request.roomIds());
        return ResponseEntity.noContent().build();
    }
}
