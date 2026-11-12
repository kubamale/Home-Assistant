package dev.kuba.homedisplay.rooms;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import dev.kuba.homedisplay.users.Viewer;

/**
 * Who may see and control the devices of a room. Every path that shows or changes a device (the app, scenes, favourites,
 * the assistant) asks here, so a room's setting is enforced in one place.
 *
 * <ul>
 *   <li>{@code open}: everyone sees and controls.</li>
 *   <li>{@code view}: everyone sees, only the listed people control.</li>
 *   <li>{@code private}: only the listed people see and control.</li>
 * </ul>
 *
 * The admin always can. A display is its owner, so it can do what its owner can. A device without a room (or in a room that
 * no longer exists) is open to everyone.
 */
@Service
public class RoomAccess {

    public static final String OPEN = "open";
    public static final String VIEW = "view";
    public static final String PRIVATE = "private";

    /** One room's setting and the people it names. */
    public record Policy(String access, Set<Long> members) {
    }

    private final RoomRepository rooms;

    public RoomAccess(RoomRepository rooms) {
        this.rooms = rooms;
    }

    /** What one person may do, with the rooms' settings read once so a whole list is checked consistently. */
    public For of(Viewer viewer) {
        return new For(viewer, rooms.policies());
    }

    public static final class For {

        private final Viewer viewer;
        private final Map<Long, Policy> policies;

        For(Viewer viewer, Map<Long, Policy> policies) {
            this.viewer = viewer;
            this.policies = policies;
        }

        public boolean canSee(Long roomId) {
            Policy policy = roomId == null ? null : policies.get(roomId);
            if (policy == null || viewer.admin() || !PRIVATE.equals(policy.access())) {
                return true;
            }
            return policy.members().contains(viewer.userId());
        }

        public boolean canControl(Long roomId) {
            Policy policy = roomId == null ? null : policies.get(roomId);
            if (policy == null || viewer.admin() || OPEN.equals(policy.access())) {
                return true;
            }
            return policy.members().contains(viewer.userId());
        }
    }
}
