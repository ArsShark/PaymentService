package com.innowise.paymentservice.controller;

import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PageResponse;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.security.AccessRules;
import com.innowise.paymentservice.security.Callers;
import com.innowise.paymentservice.service.Caller;
import com.innowise.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST API of the payments.
 *
 * <p>The controller is thin: it builds the {@code Caller} out of the verified token, turns the
 * query parameters into a {@code PaymentFilter} and calls the service. Whose payment it is, what
 * the outcome of a payment is and what the answers are is decided in the service layer; the
 * exceptions are mapped to statuses by {@code GlobalExceptionHandler}. The payer of a new payment
 * is the profile of the token, never a field of the body, and a payment never changes: there is
 * no endpoint that updates or deletes one.
 *
 * <p>A payment whose status is {@code FAILED} is still created: the answer is 201, and the
 * outcome is data in the body.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  /**
   * The page size when the request has none. It is written down because {@code @PageableDefault}
   * has a size of its own (10) that replaces the default of Spring Data (20).
   */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /** The client sorts by the names of the JSON: the amount is {@code paymentAmount} inside. */
  private static final SortWhitelist SORT = SortWhitelist
    .of("timestamp", "orderId", "userId", "status")
    .withAlias("amount", "paymentAmount");

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  /**
   * Makes a payment of the profile of the token: 201 with the {@code Location}, whatever the
   * outcome is. 409 when the order is already paid, 502 or 504 when the random number service
   * cannot decide (nothing is saved then).
   */
  @PostMapping
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<PaymentResponse> create(Authentication authentication,
    @Valid @RequestBody PaymentRequest request) {
    PaymentResponse created = paymentService.create(Callers.owner(authentication), request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
      .path("/{id}").buildAndExpand(created.id()).toUri();
    return ResponseEntity.created(location).body(created);
  }

  /** Returns one payment: 200, or 404 for a missing payment and for one of somebody else. */
  @GetMapping("/{id}")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<PaymentResponse> getById(@PathVariable String id,
    Authentication authentication) {
    return ResponseEntity.ok(paymentService.getById(id, Callers.from(authentication)));
  }

  /**
   * Returns a page of the payments of everybody that match the filter. Admin only. The
   * parameters are optional and combined with AND.
   */
  @GetMapping
  @PreAuthorize(AccessRules.ADMIN_ONLY)
  public ResponseEntity<PageResponse<PaymentResponse>> getAll(
    @RequestParam(required = false) Long userId,
    @RequestParam(required = false) Long orderId,
    @RequestParam(required = false) PaymentStatus status,
    @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "timestamp",
      direction = Sort.Direction.DESC) Pageable pageable) {
    PaymentFilter filter = new PaymentFilter(userId, orderId, status);
    return ResponseEntity.ok(
      PageResponse.from(paymentService.findAll(filter, SORT.apply(pageable))));
  }

  /**
   * Returns a page of the own payments of the profile of the token. There is no {@code userId}
   * parameter: the payer is always the caller.
   */
  @GetMapping("/my")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<PageResponse<PaymentResponse>> getMy(Authentication authentication,
    @RequestParam(required = false) Long orderId,
    @RequestParam(required = false) PaymentStatus status,
    @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "timestamp",
      direction = Sort.Direction.DESC) Pageable pageable) {
    Caller owner = Callers.owner(authentication);
    PaymentFilter filter = new PaymentFilter(null, orderId, status);
    return ResponseEntity.ok(PageResponse.from(
      paymentService.findOwn(owner.userId(), filter, SORT.apply(pageable))));
  }

  /**
   * Returns the total of the successful payments of the profile of the token in the period
   * {@code [from, before)}. Both bounds are required ISO-8601 instants; an empty period is a 400.
   */
  @GetMapping("/my/total")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<PaymentTotalResponse> getMyTotal(Authentication authentication,
    @RequestParam Instant from, @RequestParam Instant before) {
    Caller owner = Callers.owner(authentication);
    return ResponseEntity.ok(paymentService.totalOfUser(owner.userId(), from, before));
  }

  /**
   * Returns the total of the successful payments of all users in the period
   * {@code [from, before)}. Admin only.
   */
  @GetMapping("/total")
  @PreAuthorize(AccessRules.ADMIN_ONLY)
  public ResponseEntity<PaymentTotalResponse> getTotal(@RequestParam Instant from,
    @RequestParam Instant before) {
    return ResponseEntity.ok(paymentService.totalOfAll(from, before));
  }
}
