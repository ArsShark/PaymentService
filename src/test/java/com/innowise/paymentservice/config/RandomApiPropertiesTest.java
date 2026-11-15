package com.innowise.paymentservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RandomApiPropertiesTest {

  private static final Duration TWO = Duration.ofSeconds(2);

  @Test
  void validSettingsAreKept() {
    RandomApiProperties properties = new RandomApiProperties("https://www.random.org",
      TWO, Duration.ofSeconds(5));

    assertAll(
      () -> assertEquals("https://www.random.org", properties.baseUri()),
      () -> assertEquals(TWO, properties.connectTimeout()),
      () -> assertEquals(Duration.ofSeconds(5), properties.readTimeout()));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void aMissingAddressStopsTheStartup(String baseUri) {
    assertThrows(IllegalArgumentException.class, () -> new RandomApiProperties(baseUri, TWO, TWO));
  }

  @Test
  void timeoutsMustBePositive() {
    assertAll(
      () -> assertThrows(IllegalArgumentException.class,
        () -> new RandomApiProperties("http://x", Duration.ZERO, TWO)),
      () -> assertThrows(IllegalArgumentException.class,
        () -> new RandomApiProperties("http://x", TWO, Duration.ofSeconds(-1))));
  }
}
