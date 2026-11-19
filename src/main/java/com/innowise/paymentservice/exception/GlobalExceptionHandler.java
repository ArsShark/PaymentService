package com.innowise.paymentservice.exception;

import com.innowise.paymentservice.dto.response.FieldViolation;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives the same format for the exceptions
 * raised by Spring MVC itself (malformed JSON, wrong HTTP method and so on). This class adds the
 * domain exceptions, request validation failures, the failures of the database and a last-resort
 * handler.
 *
 * <p>Responses never contain rejected values or internal exception messages. The messages of the
 * domain exceptions are safe: they are constants, or they hold an order id that was already read
 * as a number.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred";

  /** The detail of every 401: it never says why, so a missing and a wrong credential look alike. */
  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  /** The detail of every 403. */
  private static final String ACCESS_DENIED = "Access is denied";

  private static final int MAX_CAUSE_DEPTH = 10;

  /** Maps "payment does not exist" (or belongs to somebody else) to 404. */
  @ExceptionHandler(PaymentNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(PaymentNotFoundException ex) {
    return reject(HttpStatus.NOT_FOUND, ex);
  }

  /** Maps a second successful payment of an order to 409. */
  @ExceptionHandler(OrderAlreadyPaidException.class)
  public ResponseEntity<ProblemDetail> handleConflict(OrderAlreadyPaidException ex) {
    return reject(HttpStatus.CONFLICT, ex);
  }

  /** Maps a request to sort by a property that is not part of the API, or a bad period, to 400. */
  @ExceptionHandler({InvalidSortPropertyException.class, InvalidPeriodException.class})
  public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex) {
    return reject(HttpStatus.BAD_REQUEST, ex);
  }

  /**
   * Maps a random number service that could not give a number to 502. Nothing was saved, so the
   * client may send the payment again.
   */
  @ExceptionHandler(RandomNumberUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleBadGateway(RandomNumberUnavailableException ex) {
    return fail(HttpStatus.BAD_GATEWAY, ex);
  }

  /** Maps a random number service that did not answer in time to 504. */
  @ExceptionHandler(RandomNumberTimeoutException.class)
  public ResponseEntity<ProblemDetail> handleGatewayTimeout(RandomNumberTimeoutException ex) {
    return fail(HttpStatus.GATEWAY_TIMEOUT, ex);
  }

  /**
   * Handles every failure of the database: always a 500, with a constant detail.
   *
   * <p>The exception itself is not logged, and no stack trace: the message of a MongoDB error
   * can hold the document that was refused (a duplicate key error does, a failed validation of
   * the collection does). Only the class of the exception, the class of its root cause and, for
   * a unique index, the name of the index are. A duplicate key of the index of successful
   * payments never gets here, the service turns it into {@link OrderAlreadyPaidException}; any
   * other one is a bug.
   */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex) {
    LOGGER.error("Unexpected failure of the database: {}", describe(ex));
    return internalError();
  }

  /**
   * Handles a caller that is not authenticated: 401 with a constant detail.
   *
   * <p>The exceptions of the filter chain get here through {@code ProblemDetailSecurityHandler};
   * the message of the exception is never sent, because it may describe the token.
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    return reject(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, ex);
  }

  /**
   * Handles an authenticated caller that has no right to the resource: 403 with a constant
   * detail. Without this handler the catch-all below would turn it into a 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return reject(HttpStatus.FORBIDDEN, ACCESS_DENIED, ex);
  }

  /** Last resort: logs the exception with its stack trace and hides the details from the client. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    LOGGER.error("Unhandled exception", ex);
    return internalError();
  }

  /** Adds the list of invalid fields (without rejected values) to the 400 response. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
    MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status,
    WebRequest request) {
    List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
      .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
      .sorted(Comparator.comparing(FieldViolation::field)
        .thenComparing(FieldViolation::message))
      .toList();
    ProblemDetail problem =
      ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("errors", violations);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  /**
   * Answers a parameter that cannot be converted (a bad status, an instant that is not ISO-8601,
   * a text instead of a number). Spring's own detail repeats the value of the client; this one
   * names only the parameter, which is part of our API.
   */
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
    HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String name = ex instanceof MethodArgumentTypeMismatchException mismatch
      ? mismatch.getName() : ex.getPropertyName();
    String detail = name == null
      ? "Invalid value of a request parameter"
      : String.format("Invalid value of the parameter '%s'", name);
    return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail),
      headers, status, request);
  }

  /**
   * Describes a failure of the database for the log: the classes of the exception and of its
   * root cause and the name of the violated unique index, never a message.
   */
  static String describe(DataAccessException ex) {
    Throwable root = ex;
    for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
      root = root.getCause();
    }
    return String.format("%s, root cause %s, index %s", ex.getClass().getSimpleName(),
      root.getClass().getSimpleName(), ViolatedIndex.nameOf(ex));
  }

  private static ResponseEntity<ProblemDetail> reject(HttpStatusCode status, RuntimeException ex) {
    LOGGER.info("Request rejected with status {}: {}", status.value(), ex.getMessage());
    return ResponseEntity.status(status)
      .body(ProblemDetail.forStatusAndDetail(status, ex.getMessage()));
  }

  /** A rejection with a fixed detail: only the class of the exception is logged. */
  private static ResponseEntity<ProblemDetail> reject(HttpStatusCode status, String detail,
    Exception ex) {
    LOGGER.info("Request rejected with status {}: {}", status.value(),
      ex.getClass().getSimpleName());
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  /** A failure of the random number service: not the fault of the client, so a warning. */
  private static ResponseEntity<ProblemDetail> fail(HttpStatusCode status, RuntimeException ex) {
    LOGGER.warn("Request failed with status {}: {}", status.value(), ex.getMessage());
    return ResponseEntity.status(status)
      .body(ProblemDetail.forStatusAndDetail(status, ex.getMessage()));
  }

  private static ResponseEntity<ProblemDetail> internalError() {
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status)
      .body(ProblemDetail.forStatusAndDetail(status, INTERNAL_ERROR_DETAIL));
  }
}
