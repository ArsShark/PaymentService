package com.innowise.paymentservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the random number service is and how long to wait for it.
 *
 * @param baseUri the address of the service, for example {@code https://www.random.org}; it
 *     comes from {@code RANDOM_API_URI}. It is the same in every environment, so the default is
 *     in {@code application.yml}; tests and a local stand-in replace it
 * @param connectTimeout how long to wait for the connection to open
 * @param readTimeout how long to wait for the answer after the request was sent
 */
@ConfigurationProperties(prefix = "app.random-api")
public record RandomApiProperties(
  String baseUri,
  @DefaultValue("2s") Duration connectTimeout,
  @DefaultValue("5s") Duration readTimeout) {

  /** Refuses a missing address and timeouts that are not positive, so the startup fails. */
  public RandomApiProperties {
    if (baseUri == null || baseUri.isBlank()) {
      throw new IllegalArgumentException("app.random-api.base-uri must not be blank");
    }
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalArgumentException("app.random-api.connect-timeout must be positive");
    }
    if (readTimeout.isZero() || readTimeout.isNegative()) {
      throw new IllegalArgumentException("app.random-api.read-timeout must be positive");
    }
  }
}
