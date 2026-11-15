package com.innowise.paymentservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the application clock, so the current time comes from one replaceable source. */
@Configuration
public class ClockConfig {

  /** UTC system clock. Tests may replace it with a fixed or a movable clock. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
