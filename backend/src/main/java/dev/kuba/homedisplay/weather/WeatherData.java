package dev.kuba.homedisplay.weather;

import java.util.List;

/** The weather the app shows: current conditions, today's summary and the hours of today. */
public record WeatherData(String locationName, Current current, Today today, List<Hour> hourly) {

    public record Current(
            double temperature,
            double apparentTemperature,
            String condition,
            String conditionLabel,
            double windSpeed,
            double precipitation,
            boolean isDay) {
    }

    public record Today(
            double minTemperature,
            double maxTemperature,
            Integer precipitationProbabilityMax,
            String condition,
            String conditionLabel,
            String sunrise,
            String sunset) {
    }

    public record Hour(String time, double temperature, Integer precipitationProbability, String condition) {
    }
}
