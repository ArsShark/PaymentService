package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.TestcontainersConfiguration;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.repository.PaymentRepository;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Proves that the queries of the repository use the indexes of the collection.
 *
 * <p>The test does not build a second copy of a query to explain it. It switches on the profiler
 * of MongoDB, calls the repository, and reads what the server recorded for the command that the
 * repository really sent: {@code planSummary} names the plan, for example
 * {@code IXSCAN { user_id: 1, timestamp: -1 }}. The profiler is switched off again afterwards.
 *
 * <p>Which index wins when several are possible is the choice of the query planner and can
 * differ on a tiny test collection; so a test names an index only when exactly one can serve the
 * query, and asserts "an index scan, not a collection scan" otherwise.
 */
class QueryPlanIntegrationTest extends AbstractIntegrationTest {

  private static final String NAMESPACE =
    String.format("%s.%s", TestcontainersConfiguration.DATABASE, PAYMENTS);

  private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("2026-12-01T00:00:00Z");

  @Autowired
  private PaymentRepository repository;

  /** Thirty payments of three users, so the planner has something to choose from. */
  @BeforeEach
  void seed() {
    for (long i = 1; i <= 30; i++) {
      PaymentStatus status = i % 2 == 0 ? PaymentStatus.FAILED : PaymentStatus.SUCCESS;
      repository.insert(payment(i, 7 + i % 3, status, FROM.plusSeconds(i * 3600), "10.00"));
    }
  }

  /** Runs the action with the profiler on and returns what the server recorded for payments. */
  private List<Document> profiled(Runnable action) {
    MongoDatabase database = mongoTemplate.getDb();
    database.runCommand(new Document("profile", 0));
    database.getCollection("system.profile").drop();
    database.runCommand(new Document("profile", 2));
    try {
      action.run();
    } finally {
      database.runCommand(new Document("profile", 0));
    }
    return database.getCollection("system.profile")
      .find(Filters.eq("ns", NAMESPACE))
      .sort(Sorts.ascending("ts"))
      .into(new ArrayList<>());
  }

  /** The plan of the first recorded command of a kind: {@code find} or {@code aggregate}. */
  private static String planOf(List<Document> entries, String commandName) {
    return entries.stream()
      .filter(entry -> entry.get("command", Document.class).containsKey(commandName))
      .filter(entry -> entry.getString("planSummary") != null)
      .map(entry -> entry.getString("planSummary"))
      .findFirst()
      .orElseThrow(() -> new AssertionError(String.format(
        "The profiler recorded no %s with a planSummary; entries: %d", commandName,
        entries.size())));
  }

  /** The plan of the first recorded command that has one, whatever the command is. */
  private static String firstPlan(List<Document> entries) {
    return entries.stream()
      .map(entry -> entry.getString("planSummary"))
      .filter(plan -> plan != null)
      .findFirst()
      .orElseThrow(() -> new AssertionError(String.format(
        "The profiler recorded no planSummary; entries: %d", entries.size())));
  }

  private String planOfList(PaymentFilter filter) {
    List<Document> entries = profiled(() -> repository.findAll(filter,
      PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "timestamp"))));
    return planOf(entries, "find");
  }

  @Test
  void theListOfAUserUsesTheIndexOfTheUser() {
    String plan = planOfList(new PaymentFilter(7L, null, null));

    assertAll(
      () -> assertTrue(plan.startsWith("IXSCAN"), plan),
      () -> assertTrue(plan.contains("user_id"), plan));
  }

  @Test
  void theListOfAnOrderUsesTheIndexOfTheOrder() {
    String plan = planOfList(new PaymentFilter(null, 5L, null));

    assertAll(
      () -> assertTrue(plan.startsWith("IXSCAN"), plan),
      () -> assertTrue(plan.contains("order_id"), plan));
  }

  @Test
  void theListOfAStatusUsesTheIndexOfTheStatus() {
    // FAILED: the partial unique index covers only SUCCESS, so only the status index can serve.
    String plan = planOfList(new PaymentFilter(null, null, PaymentStatus.FAILED));

    assertAll(
      () -> assertTrue(plan.startsWith("IXSCAN"), plan),
      () -> assertTrue(plan.contains("status"), plan));
  }

  @Test
  void aListWithSeveralFiltersStillUsesAnIndex() {
    String plan = planOfList(new PaymentFilter(7L, 5L, PaymentStatus.FAILED));

    assertTrue(plan.startsWith("IXSCAN"), plan);
  }

  @Test
  void aListWithoutAFilterReadsTheWholeCollection() {
    // A known limit, pinned so that nobody is surprised: no index starts with the time, and an
    // unfiltered list of an administrator is sorted in memory. Filtered lists are not affected.
    String plan = planOfList(PaymentFilter.any());

    assertTrue(plan.contains("COLLSCAN"), plan);
  }

  @Test
  void theCheckForASuccessfulPaymentOfAnOrderUsesAnIndex() {
    // Order 5 has a SUCCESS payment in the seed. Several indexes can serve this query (the
    // index of the order, the partial unique one), so only "an index scan" is asserted.
    List<Document> entries = profiled(() -> repository.hasSuccessfulPayment(5L));

    String plan = firstPlan(entries);

    assertAll(
      () -> assertTrue(plan.contains("IXSCAN"), plan),
      () -> assertFalse(plan.contains("COLLSCAN"), plan));
  }

  @Test
  void theTotalOfAUserUsesAnIndex() {
    List<Document> entries = profiled(() -> repository.sumOfUser(7L, FROM, BEFORE));

    String plan = planOf(entries, "aggregate");

    assertTrue(plan.startsWith("IXSCAN"), plan);
  }

  @Test
  void theTotalOfAllUsersUsesAnIndex() {
    List<Document> entries = profiled(() -> repository.sumOfAll(FROM, BEFORE));

    String plan = planOf(entries, "aggregate");

    assertTrue(plan.startsWith("IXSCAN"), plan);
  }
}
