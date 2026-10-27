package dev.kuba.homedisplay.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** The one clock for the app; "today" is derived from it plus the saved timezone. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
