package com.innowise.paymentservice.controller;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.exception.InvalidSortPropertyException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Tests of {@link SortWhitelist}. */
class SortWhitelistTest {

  /** The whitelist of the payments: the JSON says "amount", the entity "paymentAmount". */
  private final SortWhitelist whitelist = SortWhitelist
    .of("timestamp", "orderId", "userId", "status")
    .withAlias("amount", "paymentAmount");

  @Test
  void keepsAllowedSortAndAppendsIdAsTiebreaker() {
    Pageable pageable = PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "timestamp"));

    Pageable result = whitelist.apply(pageable);

    assertAll(
      () -> assertEquals(2, result.getPageNumber()),
      () -> assertEquals(10, result.getPageSize()),
      () -> assertEquals(Sort.by(Sort.Order.desc("timestamp"), Sort.Order.asc("id")),
        result.getSort()));
  }

  @Test
  void doesNotDuplicateIdWhenRequestAlreadySortsByIt() {
    Pageable result = whitelist.apply(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")));

    assertEquals(Sort.by(Sort.Order.desc("id")), result.getSort());
  }

  @Test
  void sortsByIdWhenRequestHasNoSort() {
    Pageable result = whitelist.apply(PageRequest.of(0, 20));

    assertEquals(Sort.by("id"), result.getSort());
  }

  /** The client says "amount", the query must get the property of the entity. */
  @Test
  void translatesTheNameOfTheJsonToTheProperty() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "amount"));

    Pageable result = whitelist.apply(pageable);

    assertEquals(Sort.by(Sort.Order.desc("paymentAmount"), Sort.Order.asc("id")),
      result.getSort());
  }

  @Test
  void keepsTheDirectionOfEachPropertyWhenSeveralAreRequested() {
    Pageable pageable = PageRequest.of(0, 20,
      Sort.by(Sort.Order.asc("amount"), Sort.Order.desc("status")));

    Pageable result = whitelist.apply(pageable);

    assertEquals(Sort.by(Sort.Order.asc("paymentAmount"), Sort.Order.desc("status"),
      Sort.Order.asc("id")), result.getSort());
  }

  @Test
  void keepsTheCaseFlagOfATranslatedProperty() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("amount").ignoreCase()));

    Pageable result = whitelist.apply(pageable);

    Sort.Order translated = result.getSort().getOrderFor("paymentAmount");
    assertAll(
      () -> assertEquals(Sort.Direction.DESC, translated.getDirection()),
      () -> assertTrue(translated.isIgnoreCase()));
  }

  /** The name of the entity is not part of the API: it is refused, like any unknown name. */
  @Test
  void refusesThePropertyOfTheEntityWhenOnlyTheAliasIsPublic() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by("paymentAmount"));

    assertThrows(InvalidSortPropertyException.class, () -> whitelist.apply(pageable));
  }

  @Test
  void rejectsUnknownPropertyWithoutEchoingIt() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by("password"));

    InvalidSortPropertyException exception =
      assertThrows(InvalidSortPropertyException.class, () -> whitelist.apply(pageable));

    assertAll(
      () -> assertEquals(String.format("Unsupported sort property. Allowed properties: %s",
        "[amount, id, orderId, status, timestamp, userId]"), exception.getMessage()),
      () -> assertFalse(exception.getMessage().contains("password")));
  }

  @Test
  void rejectsRequestWhenOnlyOneOfSeveralPropertiesIsUnknown() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by("timestamp", "number"));

    assertThrows(InvalidSortPropertyException.class, () -> whitelist.apply(pageable));
  }

  @Test
  void anAliasDoesNotChangeTheWhitelistItWasAddedTo() {
    SortWhitelist plain = SortWhitelist.of("status");
    plain.withAlias("amount", "paymentAmount");

    Pageable pageable = PageRequest.of(0, 20, Sort.by("amount"));

    assertThrows(InvalidSortPropertyException.class, () -> plain.apply(pageable));
  }
}
