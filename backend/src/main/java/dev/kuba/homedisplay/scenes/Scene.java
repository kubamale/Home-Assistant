package dev.kuba.homedisplay.scenes;

/**
 * A scene row. {@code kind} is {@code custom} or {@code all_off} (the built-in scene with no stored actions).
 * {@code ownerId} is the person it belongs to, or null for a scene everyone shares.
 */
public record Scene(long id, String name, int position, String kind, Long ownerId) {

    public static final String CUSTOM = "custom";
    public static final String ALL_OFF = "all_off";

    public boolean isAllOff() {
        return ALL_OFF.equals(kind);
    }

    public boolean shared() {
        return ownerId == null;
    }

    /** Anyone sees a shared scene and their own. */
    public boolean visibleTo(long userId) {
        return ownerId == null || ownerId == userId;
    }

    /** The admin edits shared scenes; everyone edits their own. */
    public boolean editableBy(long userId, boolean admin) {
        return ownerId == null ? admin : ownerId == userId;
    }
}
