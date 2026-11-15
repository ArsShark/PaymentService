package com.innowise.paymentservice.client;

import com.innowise.paymentservice.exception.RandomNumberTimeoutException;
import com.innowise.paymentservice.exception.RandomNumberUnavailableException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Asks the random number service for one number. The payment is {@code SUCCESS} when the number
 * is even and {@code FAILED} otherwise; that decision is made by the caller, this class only
 * brings the number.
 *
 * <p>The request is the legacy HTTP interface of random.org: {@code GET /integers/} with one
 * integer from 1 to 100 as plain text. Only the parity of the number is used, so the answer is
 * not checked against the range: another provider may use another one.
 *
 * <p>There are exactly two ways to fail. The service does not answer in time
 * ({@link RandomNumberTimeoutException}), or it answers with anything that is not one integer on
 * a 200 (an error status, a refusal for an exhausted quota, an empty or garbled body, a dropped
 * connection): {@link RandomNumberUnavailableException}. There is no retry and no circuit
 * breaker: a payment that cannot get its number is refused at once, and nothing is saved.
 *
 * <p>The causes are logged with the status or the class name only: the messages of the HTTP
 * client hold the address, and a body is not ours to repeat.
 */
@Component
public class RandomNumberClient {

  /** The request: one integer, from 1 to 100, in one column, in base 10, as plain text. */
  static final String REQUEST =
    "/integers/?num=1&min=1&max=100&col=1&base=10&format=plain&rnd=new";

  /** What a usable answer looks like once the line break around it is cut off. */
  private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

  private static final Logger LOGGER = LoggerFactory.getLogger(RandomNumberClient.class);

  private final RestClient restClient;

  public RandomNumberClient(@Qualifier("randomApiRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Asks for one random integer.
   *
   * @return the number
   * @throws RandomNumberTimeoutException if the service did not connect or answer in time
   * @throws RandomNumberUnavailableException for every other failure
   */
  public long nextNumber() {
    ResponseEntity<String> response;
    try {
      response = restClient.get().uri(REQUEST).retrieve().toEntity(String.class);
    } catch (RestClientException e) {
      throw translate(e);
    }
    int status = response.getStatusCode().value();
    if (status != 200) {
      // A 3xx is not followed and a 2xx other than 200 carries no number.
      LOGGER.warn("The random number service answered with status {}", status);
      throw new RandomNumberUnavailableException();
    }
    return parse(response.getBody());
  }

  private static long parse(String body) {
    String text = body == null ? "" : body.strip();
    if (!INTEGER.matcher(text).matches()) {
      LOGGER.warn("The random number service answered with something that is not a number");
      throw new RandomNumberUnavailableException();
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      LOGGER.warn("The random number service answered with a number that does not fit a long");
      throw new RandomNumberUnavailableException();
    }
  }

  private static RuntimeException translate(RestClientException error) {
    if (error instanceof RestClientResponseException response) {
      // random.org answers 503 with a line "Error: ..." when its quota is used up.
      LOGGER.warn("The random number service answered with status {}",
        response.getStatusCode().value());
      return new RandomNumberUnavailableException();
    }
    if (error instanceof ResourceAccessException access && isTimeout(access)) {
      LOGGER.warn("The call to the random number service ran out of time ({})",
        rootClass(access));
      return new RandomNumberTimeoutException();
    }
    LOGGER.warn("The call to the random number service failed ({})", rootClass(error));
    return new RandomNumberUnavailableException();
  }

  private static boolean isTimeout(ResourceAccessException error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      // HttpConnectTimeoutException is a HttpTimeoutException too.
      if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  private static String rootClass(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName();
  }
}
