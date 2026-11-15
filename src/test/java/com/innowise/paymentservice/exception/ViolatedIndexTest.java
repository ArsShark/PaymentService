package com.innowise.paymentservice.exception;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Tests how the name of a violated index is cut out of the messages. No database is needed. */
class ViolatedIndexTest {

  /** The shape of the message that the driver builds for a duplicate key. */
  private static final String DRIVER_MESSAGE = """
    Write operation error on server localhost:27017. Write error: WriteError{code=11000, \
    message='E11000 duplicate key error collection: payment_service_db.payments index: \
    uq_payments_order_id_success dup key: { order_id: 1 }', details={}}.""";

  @Test
  void theNameIsTakenFromTheMessageOfTheDriver() {
    assertEquals(ViolatedIndex.UNIQUE_ORDER_SUCCESS,
      ViolatedIndex.nameOf(new RuntimeException(DRIVER_MESSAGE)));
  }

  @Test
  void theNameIsFoundInTheCauseWhenTheWrapperHasAnotherMessage() {
    RuntimeException wrapper = new RuntimeException("could not save",
      new IllegalStateException(DRIVER_MESSAGE));

    assertEquals(ViolatedIndex.UNIQUE_ORDER_SUCCESS, ViolatedIndex.nameOf(wrapper));
  }

  @Test
  void theIndexOfTheIdIsNamedToo() {
    String message = """
      E11000 duplicate key error collection: db.payments index: _id_ dup key: \
      { _id: ObjectId('6916f8a0c1b2d3e4f5a6b7c8') }""";

    assertEquals("_id_", ViolatedIndex.nameOf(new RuntimeException(message)));
  }

  @Test
  void aMessageWithoutAnIndexGivesNull() {
    assertAll(
      () -> assertNull(ViolatedIndex.nameOf(null)),
      () -> assertNull(ViolatedIndex.nameOf(new RuntimeException())),
      () -> assertNull(ViolatedIndex.nameOf(new RuntimeException("connection refused"))),
      () -> assertNull(ViolatedIndex.nameOf(new RuntimeException("index: broken"))));
  }

  @Test
  void aCircularChainOfCausesEnds() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);

    assertNull(ViolatedIndex.nameOf(first));
  }
}
