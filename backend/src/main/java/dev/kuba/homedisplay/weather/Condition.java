package dev.kuba.homedisplay.weather;

/** Maps WMO weather codes (as used by Open-Meteo) to a small set of conditions the UI can draw. */
public enum Condition {
    CLEAR("clear", "Clear"),
    MOSTLY_CLEAR("mostly-clear", "Mostly clear"),
    PARTLY_CLOUDY("partly-cloudy", "Partly cloudy"),
    CLOUDY("cloudy", "Overcast"),
    FOG("fog", "Fog"),
    DRIZZLE("drizzle", "Drizzle"),
    RAIN("rain", "Rain"),
    FREEZING_RAIN("freezing-rain", "Freezing rain"),
    SHOWERS("showers", "Showers"),
    SNOW("snow", "Snow"),
    THUNDERSTORM("thunderstorm", "Thunderstorm"),
    UNKNOWN("unknown", "Unknown");

    private final String key;
    private final String label;

    Condition(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static Condition fromWmo(int code) {
        return switch (code) {
            case 0 -> CLEAR;
            case 1 -> MOSTLY_CLEAR;
            case 2 -> PARTLY_CLOUDY;
            case 3 -> CLOUDY;
            case 45, 48 -> FOG;
            case 51, 53, 55 -> DRIZZLE;
            case 56, 57, 66, 67 -> FREEZING_RAIN;
            case 61, 63, 65 -> RAIN;
            case 71, 73, 75, 77, 85, 86 -> SNOW;
            case 80, 81, 82 -> SHOWERS;
            case 95, 96, 99 -> THUNDERSTORM;
            default -> UNKNOWN;
        };
    }
}
