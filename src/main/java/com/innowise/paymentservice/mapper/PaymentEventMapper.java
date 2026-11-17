package com.innowise.paymentservice.mapper;

import com.innowise.paymentservice.dto.event.PaymentEvent;
import com.innowise.paymentservice.entity.Payment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Builds the event of a saved payment.
 *
 * <p>The event is a function of the payment alone: no clock and no random number. So sending the
 * same payment again (a retry, a later republishing) gives the same event, and the test needs
 * no seams.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PaymentEventMapper {

  /**
   * Maps a payment that has an id (it must have been saved) to its event.
   *
   * @param payment a saved payment
   * @return the event {@code CREATE_PAYMENT} of the payment
   */
  @Mapping(target = "eventId", source = "id", qualifiedByName = "eventIdOf")
  @Mapping(target = "eventType", constant = PaymentEvent.CREATE_PAYMENT)
  @Mapping(target = "paymentId", source = "id")
  @Mapping(target = "amount", source = "paymentAmount", qualifiedByName = "amountText")
  @Mapping(target = "occurredAt", source = "timestamp")
  PaymentEvent toEvent(Payment payment);

  /**
   * The id of the event: a name-based UUID of the event type and the payment id, so it is the
   * same every time for one payment.
   */
  @Named("eventIdOf")
  default UUID eventIdOf(String paymentId) {
    String name = String.format("%s:%s", PaymentEvent.CREATE_PAYMENT, paymentId);
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  /** The amount as plain text with its scale ({@code 24.00}, never {@code 2.4E+1}). */
  @Named("amountText")
  default String amountText(BigDecimal amount) {
    return amount.toPlainString();
  }
}
