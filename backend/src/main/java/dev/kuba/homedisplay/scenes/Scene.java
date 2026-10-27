package dev.kuba.homedisplay.scenes;

/** A scene row. {@code kind} is {@code custom} or {@code all_off} (the built-in scene with no stored actions). */
public record Scene(long id, String name, int position, String kind) {

    public static final String CUSTOM = "custom";
    public static final String ALL_OFF = "all_off";

    public boolean isAllOff() {
        return ALL_OFF.equals(kind);
    }
}
