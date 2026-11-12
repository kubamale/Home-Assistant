package dev.kuba.homedisplay.displays;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * How a wall display looks: up to three columns, each an ordered stack of widgets, and whether the display listens for
 * its wake word, and whether the background moves with the weather (rain falls, clouds drift; off for a slow screen).
 * A widget is a kind of card ({@code type}) with the one setting that kind has: {@code count} (how many
 * hours, events or tasks to show) for the weather, the calendar and the tasks, {@code hour12} for the clock. Each kind
 * appears at most once, so what is on the screen is what the person picked and nothing overflows it.
 */
public record DisplayLayout(List<Column> columns, boolean voice, Boolean motion) {

    public static final int MAX_COLUMNS = 3;
    public static final int MAX_PER_COLUMN = 4;

    public static final String CLOCK = "clock";
    public static final String WEATHER = "weather";
    public static final String CALENDAR = "calendar";
    public static final String TASKS = "tasks";
    public static final String QUICK_ACCESS = "quickAccess";
    public static final List<String> TYPES = List.of(CLOCK, WEATHER, CALENDAR, TASKS, QUICK_ACCESS);

    public record Column(List<Widget> widgets) {
    }

    public record Widget(String type, Integer count, Boolean hour12) {
    }

    /** What every display starts with: the clock and the weather, the calendar, the tasks and the pinned lights. */
    public static DisplayLayout defaults() {
        return new DisplayLayout(List.of(
                new Column(List.of(new Widget(CLOCK, null, false), new Widget(WEATHER, 5, null))),
                new Column(List.of(new Widget(CALENDAR, 5, null))),
                new Column(List.of(new Widget(TASKS, 6, null), new Widget(QUICK_ACCESS, null, null)))), true, true);
    }

    /** The layout with its settings filled in and checked, or a 400 saying what is wrong. */
    public DisplayLayout validated() {
        if (columns == null || columns.isEmpty() || columns.size() > MAX_COLUMNS) {
            throw bad("A display has 1 to " + MAX_COLUMNS + " columns");
        }
        Set<String> seen = new HashSet<>();
        List<Column> cleaned = new ArrayList<>();
        for (Column column : columns) {
            List<Widget> widgets = column == null || column.widgets() == null ? List.of() : column.widgets();
            if (widgets.isEmpty() || widgets.size() > MAX_PER_COLUMN) {
                throw bad("Each column holds 1 to " + MAX_PER_COLUMN + " cards");
            }
            List<Widget> out = new ArrayList<>();
            for (Widget widget : widgets) {
                if (widget == null || widget.type() == null || !TYPES.contains(widget.type())) {
                    throw bad("Unknown card");
                }
                if (!seen.add(widget.type())) {
                    throw bad("Each card can only be on the screen once");
                }
                out.add(fill(widget));
            }
            cleaned.add(new Column(List.copyOf(out)));
        }
        // a layout saved before motion existed (or sent without it) keeps the background still, as it was
        return new DisplayLayout(List.copyOf(cleaned), voice, Boolean.TRUE.equals(motion));
    }

    private static Widget fill(Widget w) {
        return switch (w.type()) {
            case CLOCK -> new Widget(CLOCK, null, Boolean.TRUE.equals(w.hour12()));
            case WEATHER -> new Widget(WEATHER, within(w.count(), 5, 3, 8, "hours of weather"), null);
            case CALENDAR -> new Widget(CALENDAR, within(w.count(), 5, 2, 8, "events"), null);
            case TASKS -> new Widget(TASKS, within(w.count(), 6, 2, 8, "tasks"), null);
            default -> new Widget(w.type(), null, null);
        };
    }

    private static int within(Integer value, int fallback, int min, int max, String what) {
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            throw bad("Show " + min + " to " + max + " " + what);
        }
        return value;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
