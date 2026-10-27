package dev.kuba.homedisplay.stream;

/** Lets services announce "something changed" without knowing about SSE. */
public interface ChangePublisher {

    String TASKS = "tasks.changed";
    String DEVICES = "devices.changed";
    String HOME = "home.changed";
    String WEATHER = "weather.changed";
    String CALENDAR = "calendar.changed";
    String SETTINGS = "settings.changed";

    void publish(String event);
}
