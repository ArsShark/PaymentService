package com.innowise.paymentservice.exception;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out which unique index of MongoDB an exception violated.
 *
 * <p>MongoDB reports the index only in the text of the error
 * ({@code E11000 duplicate key error collection: db.payments index: <name> dup key: ...}), there
 * is no separate field for it. The name is cut out of that text, and a test on a real MongoDB
 * container pins the format, so a change of the message in a future version fails the build and
 * is not noticed in production. Only the NAME of the index is returned: the rest of the message
 * holds the values of the rejected document and must not reach a log or a response.
 */
public final class ViolatedIndex {

  /** The partial unique index: an order has at most one payment with the status SUCCESS. */
  public static final String UNIQUE_ORDER_SUCCESS = "uq_payments_order_id_success";

  private static final Pattern INDEX = Pattern.compile("index: (\\S+) dup key");

  private static final int MAX_CAUSE_DEPTH = 10;

  private ViolatedIndex() {
  }

  /**
   * Looks for the name of the violated index in the messages of the exception and its causes.
   *
   * @param throwable the exception thrown by Spring Data, may be {@code null}
   * @return the name of the index, or {@code null} if no message names one
   */
  public static String nameOf(Throwable throwable) {
    Throwable cause = throwable;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      String message = cause.getMessage();
      if (message != null) {
        Matcher matcher = INDEX.matcher(message);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
      cause = cause.getCause();
    }
    return null;
  }
}
