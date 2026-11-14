package com.innowise.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.config.MongoMigration;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the schema of the collection {@code payments} on a real MongoDB: the validator, the
 * indexes, the types of the stored fields and the rule "one successful payment per order".
 *
 * <p>Documents that must be refused are inserted as plain BSON documents through the driver,
 * not through the entity, because the entity cannot build an invalid payment: the test has to
 * play a client that bypasses it.
 */
class SchemaIntegrationTest extends AbstractIntegrationTest {

  /** MongoDB error code: the document does not match the validator. */
  private static final int DOCUMENT_VALIDATION_FAILURE = 121;

  /** MongoDB error code: a unique index already holds the key. */
  private static final int DUPLICATE_KEY = 11000;

  @Autowired
  private MongoMigration mongoMigration;

  private MongoCollection<Document> payments() {
    return mongoTemplate.getCollection(PAYMENTS);
  }

  /** A document that the validator accepts. */
  private static Document validDocument() {
    return new Document("order_id", 1L)
      .append("user_id", 7L)
      .append("status", "SUCCESS")
      .append("timestamp", new Date())
      .append("payment_amount", new Decimal128(new BigDecimal("24.00")));
  }

  private static Document document(long orderId, String status) {
    return validDocument().append("order_id", orderId).append("status", status);
  }

  private static Document without(String field) {
    Document document = validDocument();
    document.remove(field);
    return document;
  }

  private static Decimal128 decimal(String value) {
    return new Decimal128(new BigDecimal(value));
  }

  @Test
  void theCollectionHasTheJsonSchemaValidator() {
    Document info = mongoTemplate.getDb().listCollections()
      .filter(Filters.eq("name", PAYMENTS)).first();
    assertNotNull(info);

    Document options = info.get("options", Document.class);
    Document schema = options.get("validator", Document.class).get("$jsonSchema", Document.class);
    assertAll(
      () -> assertEquals("error", options.getString("validationAction")),
      () -> assertEquals("strict", options.getString("validationLevel")),
      () -> assertEquals(
        List.of("order_id", "user_id", "status", "timestamp", "payment_amount"),
        schema.getList("required", String.class)));
  }

  @Test
  void theCollectionHasExactlyTheIndexesOfTheChangelog() {
    Map<String, Document> indexes = indexesByName();

    assertAll(
      () -> assertEquals(Set.of("_id_", "idx_payments_user_id_timestamp",
        "idx_payments_order_id_timestamp", "idx_payments_status_timestamp",
        "uq_payments_order_id_success"), indexes.keySet()),
      () -> assertEquals(new Document("user_id", 1).append("timestamp", -1),
        indexes.get("idx_payments_user_id_timestamp").get("key")),
      () -> assertEquals(new Document("order_id", 1).append("timestamp", -1),
        indexes.get("idx_payments_order_id_timestamp").get("key")),
      () -> assertEquals(new Document("status", 1).append("timestamp", -1),
        indexes.get("idx_payments_status_timestamp").get("key")));
  }

  @Test
  void theUniqueIndexOfTheOrderIsPartialAndCoversOnlySuccessfulPayments() {
    Document index = indexesByName().get("uq_payments_order_id_success");
    assertNotNull(index);

    assertAll(
      () -> assertEquals(new Document("order_id", 1), index.get("key")),
      () -> assertEquals(Boolean.TRUE, index.get("unique")),
      () -> assertEquals(new Document("status", "SUCCESS"), index.get("partialFilterExpression")));
  }

  @Test
  void theChangelogIsRecordedAndApplyingItAgainChangesNothing() {
    long recorded = mongoTemplate.getCollection("DATABASECHANGELOG").countDocuments();
    Set<String> indexNames = indexesByName().keySet();

    mongoMigration.afterPropertiesSet();

    assertAll(
      // One entry per changeSet (001 and 002).
      () -> assertEquals(2, recorded),
      () -> assertEquals(recorded,
        mongoTemplate.getCollection("DATABASECHANGELOG").countDocuments()),
      () -> assertEquals(indexNames, indexesByName().keySet()));
  }

  @Test
  void aPaymentIsStoredWithTheDocumentedFieldNamesAndTypes() {
    Instant timestamp = Instant.parse("2026-11-13T10:15:30.123Z");

    Payment saved = mongoTemplate.save(
      new Payment(11L, 7L, PaymentStatus.SUCCESS, timestamp, new BigDecimal("24.00")));

    Document raw = payments().find().first();
    assertNotNull(raw);
    Set<String> fields = new HashSet<>(raw.keySet());
    // Spring Data adds the type hint _class; it is not part of the schema of the task.
    fields.remove("_class");
    assertAll(
      () -> assertTrue(ObjectId.isValid(saved.getId())),
      () -> assertEquals(
        Set.of("_id", "order_id", "user_id", "status", "timestamp", "payment_amount"), fields),
      () -> assertEquals(new ObjectId(saved.getId()), raw.get("_id")),
      () -> assertEquals(Long.valueOf(11L), raw.get("order_id")),
      () -> assertEquals(Long.valueOf(7L), raw.get("user_id")),
      () -> assertEquals("SUCCESS", raw.get("status")),
      () -> assertEquals(Date.from(timestamp), raw.get("timestamp")),
      () -> assertEquals(decimal("24.00"), raw.get("payment_amount")));
  }

  @Test
  void aPaymentReadBackIsEqualToTheOneThatWasSaved() {
    Instant timestamp = Instant.parse("2026-11-13T10:15:30.123456789Z");
    Payment saved = mongoTemplate.save(
      new Payment(11L, 7L, PaymentStatus.FAILED, timestamp, new BigDecimal("24.50")));

    Payment loaded = mongoTemplate.findById(saved.getId(), Payment.class);

    assertNotNull(loaded);
    assertAll(
      () -> assertEquals(saved.getId(), loaded.getId()),
      () -> assertEquals(11L, loaded.getOrderId()),
      () -> assertEquals(7L, loaded.getUserId()),
      () -> assertEquals(PaymentStatus.FAILED, loaded.getStatus()),
      // The entity cut the nanoseconds when it was built: what is saved is what is read.
      () -> assertEquals(Instant.parse("2026-11-13T10:15:30.123Z"), loaded.getTimestamp()),
      // The same scale: 24.50 and 24.5 are different BigDecimal values.
      () -> assertEquals(new BigDecimal("24.50"), loaded.getPaymentAmount()));
  }

  @Test
  void theDatabaseAddsUpTheAmountsExactly() {
    Instant now = Instant.parse("2026-11-13T10:00:00Z");
    mongoTemplate.save(new Payment(1L, 7L, PaymentStatus.SUCCESS, now, new BigDecimal("24.00")));
    mongoTemplate.save(new Payment(2L, 7L, PaymentStatus.SUCCESS, now, new BigDecimal("10.50")));

    Document total = payments().aggregate(List.of(
      new Document("$group", new Document("_id", "$user_id")
        .append("total", new Document("$sum", "$payment_amount"))))).first();

    assertNotNull(total);
    assertAll(
      () -> assertEquals(Long.valueOf(7L), total.get("_id")),
      () -> assertEquals(decimal("34.50"), total.get("total")));
  }

  @Test
  void theValidatorAcceptsAValidDocument() {
    // The control of the tests below: they refuse a document because of ONE changed field.
    payments().insertOne(validDocument());

    assertEquals(1, payments().countDocuments());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidDocuments")
  void theValidatorRefusesADocumentOfAnotherShape(String description, Document invalid) {
    MongoWriteException refused =
      assertThrows(MongoWriteException.class, () -> payments().insertOne(invalid));

    assertAll(
      () -> assertEquals(DOCUMENT_VALIDATION_FAILURE, refused.getError().getCode()),
      () -> assertEquals(0, payments().countDocuments()));
  }

  static Stream<Arguments> invalidDocuments() {
    return Stream.of(
      Arguments.of("the amount is a string", validDocument().append("payment_amount", "24.00")),
      Arguments.of("the amount is a double", validDocument().append("payment_amount", 24.0)),
      Arguments.of("the amount is zero",
        validDocument().append("payment_amount", decimal("0.00"))),
      Arguments.of("the amount is negative",
        validDocument().append("payment_amount", decimal("-1.00"))),
      Arguments.of("the status is unknown", validDocument().append("status", "PENDING")),
      Arguments.of("the order id is an int32", validDocument().append("order_id", 1)),
      Arguments.of("the timestamp is a string",
        validDocument().append("timestamp", "2026-11-13T10:00:00Z")),
      Arguments.of("the order id is missing", without("order_id")),
      Arguments.of("the user id is missing", without("user_id")),
      Arguments.of("the status is missing", without("status")),
      Arguments.of("the timestamp is missing", without("timestamp")),
      Arguments.of("the amount is missing", without("payment_amount")));
  }

  @Test
  void anOrderCannotHaveTwoSuccessfulPayments() {
    payments().insertOne(document(1L, "SUCCESS"));

    MongoWriteException refused = assertThrows(MongoWriteException.class,
      () -> payments().insertOne(document(1L, "SUCCESS")));

    assertAll(
      () -> assertEquals(DUPLICATE_KEY, refused.getError().getCode()),
      () -> assertEquals(1, payments().countDocuments()));
  }

  @Test
  void anOrderCanHaveManyFailedPaymentsAndThenOneSuccessfulPayment() {
    payments().insertOne(document(1L, "FAILED"));
    payments().insertOne(document(1L, "FAILED"));
    payments().insertOne(document(1L, "SUCCESS"));

    assertAll(
      () -> assertEquals(3, payments().countDocuments(Filters.eq("order_id", 1L))),
      () -> assertEquals(1, payments().countDocuments(
        Filters.and(Filters.eq("order_id", 1L), Filters.eq("status", "SUCCESS")))));
  }

  @Test
  void differentOrdersCanEachHaveASuccessfulPayment() {
    payments().insertOne(document(1L, "SUCCESS"));
    payments().insertOne(document(2L, "SUCCESS"));

    assertEquals(2, payments().countDocuments());
  }

  private Map<String, Document> indexesByName() {
    Map<String, Document> indexes = new HashMap<>();
    for (Document index : payments().listIndexes()) {
      indexes.put(index.getString("name"), index);
    }
    return indexes;
  }
}
