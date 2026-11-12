package dev.kuba.homedisplay.rooms;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import dev.kuba.homedisplay.stream.ChangePublisher;
import dev.kuba.homedisplay.users.Viewer;

@Service
public class RoomService {

    /** A room as one person sees it: its setting, whether they control it, and (for the admin) the people it names. */
    public record RoomView(long id, String name, int position, String access, boolean canControl, List<Long> members) {
    }

    private final RoomRepository rooms;
    private final RoomAccess access;
    private final ChangePublisher changes;

    public RoomService(RoomRepository rooms, RoomAccess access, ChangePublisher changes) {
        this.rooms = rooms;
        this.access = access;
        this.changes = changes;
    }

    /** Every room, whoever asks: for the admin and for code that only needs names. */
    public List<Room> list() {
        return rooms.findAll();
    }

    /** The rooms this person may see: a private room they are not in does not exist for them. */
    public List<Room> list(Viewer viewer) {
        RoomAccess.For allowed = access.of(viewer);
        return rooms.findAll().stream().filter(r -> allowed.canSee(r.id())).toList();
    }

    @Transactional(readOnly = true)
    public List<RoomView> views(Viewer viewer) {
        RoomAccess.For allowed = access.of(viewer);
        Map<Long, RoomAccess.Policy> policies = rooms.policies();
        return rooms.findAll().stream().filter(r -> allowed.canSee(r.id())).map(r -> {
            RoomAccess.Policy policy = policies.get(r.id());
            List<Long> members = viewer.admin() && policy != null ? policy.members().stream().sorted().toList() : List.of();
            return new RoomView(r.id(), r.name(), r.position(), policy == null ? RoomAccess.OPEN : policy.access(),
                    allowed.canControl(r.id()), members);
        }).toList();
    }

    /** Sets who may use a room: everyone, everyone to see and these people to control, or only these people. */
    @Transactional
    public void setAccess(long id, String level, List<Long> members) {
        rooms.findById(id).orElseThrow(RoomService::notFound);
        if (!List.of(RoomAccess.OPEN, RoomAccess.VIEW, RoomAccess.PRIVATE).contains(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose open, view or private");
        }
        List<Long> people = members == null ? List.of() : members.stream().distinct().toList();
        if (rooms.countUsers(people) != people.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Someone in the list has no account");
        }
        rooms.setAccess(id, level, RoomAccess.OPEN.equals(level) ? List.of() : people);
        changes.publish(ChangePublisher.HOME);
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
