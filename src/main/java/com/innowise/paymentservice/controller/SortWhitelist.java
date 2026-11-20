package com.innowise.paymentservice.controller;

import com.innowise.paymentservice.exception.InvalidSortPropertyException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Restricts the properties a client may sort by and makes the sort order deterministic.
 *
 * <p>The sort names are part of the API contract, so they are checked in the controller layer,
 * before the service and the database are involved. A name may differ from the property of the
 * entity (see {@link #withAlias(String, String)}): the client uses the name of the JSON, the
 * query gets the name of the entity, and the name of the entity is not accepted from outside.
 *
 * <p>The property {@code id} is always allowed and is appended as the last sort key when the
 * request does not sort by it: without a unique tiebreaker the database may return rows with
 * equal values in a different order for every page, so pages can overlap or skip items.
 */
public final class SortWhitelist {

  private static final String ID = "id";

  /** The name the client uses, mapped to the property of the entity. Sorted for the message. */
  private final Map<String, String> allowed;

  private SortWhitelist(Map<String, String> allowed) {
    this.allowed = Collections.unmodifiableMap(allowed);
  }

  /**
   * Creates a whitelist whose names are the names of the entity properties.
   *
   * @param properties properties a client may sort by; {@code id} is added automatically
   */
  public static SortWhitelist of(String... properties) {
    Map<String, String> allowed = new TreeMap<>();
    for (String property : properties) {
      allowed.put(property, property);
    }
    allowed.put(ID, ID);
    return new SortWhitelist(allowed);
  }

  /**
   * Returns a whitelist that also accepts a name that differs from the entity property.
   *
   * @param name the name the client uses (the field of the JSON)
   * @param property the property of the entity that the query sorts by
   * @return a new whitelist; this one is not changed
   */
  public SortWhitelist withAlias(String name, String property) {
    Map<String, String> copy = new TreeMap<>(allowed);
    copy.put(name, property);
    return new SortWhitelist(copy);
  }

  /**
   * Checks the requested sort and returns the same page request with the names of the entity
   * and with {@code id} as tiebreaker.
   *
   * @throws InvalidSortPropertyException if any requested property is not allowed
   */
  public Pageable apply(Pageable pageable) {
    List<Sort.Order> orders = new ArrayList<>();
    for (Sort.Order order : pageable.getSort()) {
      String property = allowed.get(order.getProperty());
      if (property == null) {
        throw new InvalidSortPropertyException(Set.copyOf(allowed.keySet()));
      }
      orders.add(order.withProperty(property));
    }
    Sort sort = Sort.by(orders);
    if (sort.getOrderFor(ID) == null) {
      sort = sort.and(Sort.by(ID));
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
  }
}
