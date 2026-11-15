package com.innowise.paymentservice.service;

import com.innowise.paymentservice.client.RandomNumberClient;
import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.exception.InvalidPeriodException;
import com.innowise.paymentservice.exception.OrderAlreadyPaidException;
import com.innowise.paymentservice.exception.PaymentNotFoundException;
import com.innowise.paymentservice.exception.ViolatedIndex;
import com.innowise.paymentservice.mapper.PaymentMapper;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.repository.PaymentRepository;
import com.innowise.paymentservice.repository.PaymentTotal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Business logic of payments.
 *
 * <p>There is no {@code @Transactional} on purpose: the service writes one document per request,
 * which MongoDB stores atomically, and a transaction would need a replica set. A payment goes
 * through the database once and never changes afterwards.
 *
 * <p>Who may see a payment is decided here from the {@link Caller}: a payment of somebody else
 * is the same {@link PaymentNotFoundException} as a missing one.
 */
@Service
public class PaymentService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepository paymentRepository;
  private final RandomNumberClient randomNumberClient;
  private final PaymentMapper paymentMapper;
  private final Clock clock;

  public PaymentService(PaymentRepository paymentRepository,
    RandomNumberClient randomNumberClient, PaymentMapper paymentMapper, Clock clock) {
    this.paymentRepository = paymentRepository;
    this.randomNumberClient = randomNumberClient;
    this.paymentMapper = paymentMapper;
    this.clock = clock;
  }

  /**
   * Pays an order: asks the random number service, saves the payment with the outcome and
   * returns it. The outcome is {@code SUCCESS} for an even number and {@code FAILED} otherwise;
   * a declined payment is a normal result and is saved too.
   *
   * <p>Steps, in this order: (1) an order that is already paid is refused, so the random number
   * service is not asked and the refusal is the same whatever number it would have returned;
   * (2) the number; (3) the payment with the time of the clock, made after the number arrived;
   * (4) the insert. A failure in step 2 saves nothing. The check of step 1 is not a guarantee
   * (two requests can pass it together), so the unique index of the database has the last word
   * in step 4.
   *
   * <p>The payer comes from the caller, never from the request. The service does not check the
   * order or the amount against the Order Service: that is done by the Order Service when it
   * receives the event.
   *
   * @throws NullPointerException if the caller has no profile id
   * @throws OrderAlreadyPaidException if the order already has a successful payment
   * @throws com.innowise.paymentservice.exception.RandomNumberUnavailableException if no number
   *   could be had
   * @throws com.innowise.paymentservice.exception.RandomNumberTimeoutException if the random
   *   number service did not answer in time
   */
  public PaymentResponse create(Caller caller, PaymentRequest request) {
    Long payer = Objects.requireNonNull(caller.userId(), "a payment needs the id of its payer");
    long orderId = request.orderId();
    if (paymentRepository.hasSuccessfulPayment(orderId)) {
      LOGGER.info("Payment refused: order {} is already paid", orderId);
      throw new OrderAlreadyPaidException(orderId);
    }

    long number = randomNumberClient.nextNumber();
    PaymentStatus status = number % 2 == 0 ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
    Payment payment = paymentMapper.toEntity(request, payer, status, Instant.now(clock));

    Payment saved = insert(payment);
    LOGGER.info("Created payment {} of order {} by user {} with status {}", saved.getId(),
      saved.getOrderId(), saved.getUserId(), saved.getStatus());
    return paymentMapper.toResponse(saved);
  }

  /**
   * Returns a payment of the caller (an admin: any payment).
   *
   * @throws PaymentNotFoundException if there is no such payment or it belongs to somebody else
   */
  public PaymentResponse getById(String id, Caller caller) {
    return paymentRepository.findById(id)
      .filter(caller::canAccess)
      .map(paymentMapper::toResponse)
      .orElseThrow(PaymentNotFoundException::new);
  }

  /** Returns a page of the payments of every user that match the filter. For admins. */
  public Page<PaymentResponse> findAll(PaymentFilter filter, Pageable pageable) {
    return paymentRepository.findAll(filter, pageable).map(paymentMapper::toResponse);
  }

  /**
   * Returns a page of the payments of one user that match the filter; the payer of the filter is
   * replaced by {@code userId}, whatever the filter said. The user is a primitive {@code long}:
   * a missing payer would remove the condition and list the payments of everybody.
   */
  public Page<PaymentResponse> findOwn(long userId, PaymentFilter filter, Pageable pageable) {
    return findAll(filter.withUserId(userId), pageable);
  }

  /**
   * Adds up the successful payments of one user in {@code [from, before)}.
   *
   * @throws NullPointerException if a bound is {@code null}
   * @throws InvalidPeriodException if the period is empty or reversed, in milliseconds
   */
  public PaymentTotalResponse totalOfUser(long userId, Instant from, Instant before) {
    Period period = Period.of(from, before);
    PaymentTotal sum = paymentRepository.sumOfUser(userId, period.from(), period.before());
    return paymentMapper.toTotalResponse(sum, period.from(), period.before());
  }

  /**
   * Adds up the successful payments of all users in {@code [from, before)}. For admins.
   *
   * @throws NullPointerException if a bound is {@code null}
   * @throws InvalidPeriodException if the period is empty or reversed, in milliseconds
   */
  public PaymentTotalResponse totalOfAll(Instant from, Instant before) {
    Period period = Period.of(from, before);
    PaymentTotal sum = paymentRepository.sumOfAll(period.from(), period.before());
    return paymentMapper.toTotalResponse(sum, period.from(), period.before());
  }

  /**
   * Saves the payment. When the unique index of successful payments refuses it, another request
   * paid the order a moment ago: that is {@link OrderAlreadyPaidException}. Any other duplicate
   * key is a bug and is passed on unchanged.
   *
   * <p>The exception of the database is not attached as a cause: its message holds the values of
   * the refused document.
   */
  private Payment insert(Payment payment) {
    try {
      return paymentRepository.insert(payment);
    } catch (DuplicateKeyException e) {
      if (ViolatedIndex.UNIQUE_ORDER_SUCCESS.equals(ViolatedIndex.nameOf(e))) {
        LOGGER.info("Payment refused: order {} was paid by another request",
          payment.getOrderId());
        throw new OrderAlreadyPaidException(payment.getOrderId());
      }
      throw e;
    }
  }

  /**
   * A period of time with the bounds cut to milliseconds. The database keeps milliseconds, so two
   * bounds that differ only below a millisecond describe an empty period even though they are
   * not equal; the check is made after the cut, and the cut bounds are the ones that are counted
   * and reported.
   */
  private record Period(Instant from, Instant before) {

    static Period of(Instant from, Instant before) {
      Instant start = Objects.requireNonNull(from, "from").truncatedTo(ChronoUnit.MILLIS);
      Instant end = Objects.requireNonNull(before, "before").truncatedTo(ChronoUnit.MILLIS);
      if (!start.isBefore(end)) {
        throw new InvalidPeriodException();
      }
      return new Period(start, end);
    }
  }
}
