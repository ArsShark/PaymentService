package com.innowise.paymentservice.mapper;

import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentTotal;
import java.time.Instant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between payments and their DTOs.
 *
 * <p>{@link Payment} has no setters: a payment is a record of what happened and never changes.
 * MapStruct therefore builds it through its public constructor. Whatever the client must not
 * decide (the payer, the outcome, the time) is a separate parameter of {@link #toEntity}, so the
 * request body cannot carry it. {@code unmappedTargetPolicy = ERROR} turns a new constructor
 * parameter that nobody maps into a compile error.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PaymentMapper {

  /** Describes a payment; the entity says {@code paymentAmount}, the API says {@code amount}. */
  @Mapping(target = "amount", source = "paymentAmount")
  PaymentResponse toResponse(Payment payment);

  /**
   * Builds a payment that is not saved yet.
   *
   * @param request   the order and the amount the client asked to pay
   * @param userId    the payer, from the token of the caller
   * @param status    the outcome, decided by the random number service
   * @param timestamp when the payment is made, from the clock of the service
   * @throws IllegalArgumentException if the amount has more than two decimal places (the request
   *     validation refuses such an amount earlier)
   */
  @Mapping(target = "orderId", source = "request.orderId")
  @Mapping(target = "userId", source = "userId")
  @Mapping(target = "status", source = "status")
  @Mapping(target = "timestamp", source = "timestamp")
  @Mapping(target = "paymentAmount", source = "request.amount")
  Payment toEntity(PaymentRequest request, long userId, PaymentStatus status, Instant timestamp);

  /**
   * Describes a total together with the period that was counted.
   *
   * @param sum    what the database added up
   * @param from   the start of the period, inclusive
   * @param before the end of the period, exclusive
   */
  @Mapping(target = "total", source = "sum.total")
  @Mapping(target = "count", source = "sum.count")
  @Mapping(target = "from", source = "from")
  @Mapping(target = "before", source = "before")
  PaymentTotalResponse toTotalResponse(PaymentTotal sum, Instant from, Instant before);
}
