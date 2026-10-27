package dev.kuba.homedisplay.rooms;

import java.util.HashSet;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.stream.ChangePublisher;

@Service
public class RoomService {

    private final RoomRepository rooms;
    private final ChangePublisher changes;

    public RoomService(RoomRepository rooms, ChangePublisher changes) {
        this.rooms = rooms;
        this.changes = changes;
    }

    public List<Room> list() {
        return rooms.findAll();
    }

    @Transactional
    public Room create(String name) {
        rejectDuplicate(name.trim(), -1);
        Room room = rooms.insert(name.trim());
        changes.publish(ChangePublisher.HOME);
        return room;
    }

    @Transactional
    public Room rename(long id, String name) {
        rooms.findById(id).orElseThrow(RoomService::notFound);
        rejectDuplicate(name.trim(), id);
        rooms.rename(id, name.trim());
        Room room = rooms.findById(id).orElseThrow(RoomService::notFound);
        changes.publish(ChangePublisher.HOME);
        return room;
    }

    /** Devices in the room are kept and become unassigned (foreign key: on delete set null). */
    @Transactional
    public void delete(long id) {
        if (!rooms.delete(id)) {
            throw notFound();
        }
        changes.publish(ChangePublisher.HOME);
    }

    /** The list must contain every existing room exactly once. */
    @Transactional
    public void reorder(List<Long> ids) {
        List<Long> existing = rooms.findAll().stream().map(Room::id).toList();
        if (ids.size() != existing.size() || !new HashSet<>(ids).equals(new HashSet<>(existing))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "List every room exactly once");
        }
        for (int i = 0; i < ids.size(); i++) {
            rooms.setPosition(ids.get(i), i);
        }
        changes.publish(ChangePublisher.HOME);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such room");
    }

    /** The unique index backs this up; checking first gives a clean 409 instead of a database error. */
    private void rejectDuplicate(String name, long exceptId) {
        if (rooms.nameTaken(name, exceptId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A room with that name already exists");
        }
    }
}
