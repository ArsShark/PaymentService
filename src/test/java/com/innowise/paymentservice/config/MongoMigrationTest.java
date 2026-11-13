package com.innowise.paymentservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests the parts of {@link MongoMigration} that need no database: the connection string, the
 * checks of the settings and the scrubbing of a failure. That the changelog really is applied is
 * proved by the integration tests.
 */
class MongoMigrationTest {

  /**
   * Characters that break an unencoded connection string: at, colon, slash, plus, percent and
   * a space (a space is written as %20, not as a plus sign).
   */
  private static final String AWKWARD_PASSWORD = "p@ss:w/rd+1% x";

  private static MockEnvironment environment() {
    return new MockEnvironment()
      .withProperty("spring.mongodb.host", "mongo.example")
      .withProperty("spring.mongodb.port", "27018")
      .withProperty("spring.mongodb.database", "payments_db");
  }

  @Test
  void theConnectionStringCarriesTheEncodedCredentialsAndTheTimeouts() {
    String url = MongoMigration.connectionUrl(
      "mongo.example", 27018, "payments_db", "pay user", AWKWARD_PASSWORD, "admin");

    ConnectionString parsed = new ConnectionString(url);
    MongoCredential credential = parsed.getCredential();
    assertNotNull(credential);
    assertAll(
      () -> assertEquals(List.of("mongo.example:27018"), parsed.getHosts()),
      () -> assertEquals("payments_db", parsed.getDatabase()),
      // The driver decodes what the migration encoded, whatever the characters are.
      () -> assertEquals("pay user", credential.getUserName()),
      () -> assertEquals(AWKWARD_PASSWORD, new String(credential.getPassword())),
      () -> assertEquals("admin", credential.getSource()),
      () -> assertEquals(2000, parsed.getConnectTimeout()),
      () -> assertEquals(5000, parsed.getServerSelectionTimeout()));
  }

  @Test
  void withoutAUsernameTheConnectionStringHasNoCredentials() {
    String url = MongoMigration.connectionUrl(
      "localhost", 27017, "payments_db", null, null, "admin");

    ConnectionString parsed = new ConnectionString(url);
    assertAll(
      () -> assertNull(parsed.getCredential()),
      () -> assertFalse(url.contains("@")),
      () -> assertEquals("payments_db", parsed.getDatabase()));
  }

  @Test
  void aMissingHostOrDatabaseStopsTheStartup() {
    MockEnvironment noHost = new MockEnvironment()
      .withProperty("spring.mongodb.database", "payments_db");
    MockEnvironment noDatabase = new MockEnvironment()
      .withProperty("spring.mongodb.host", "localhost");

    assertAll(
      () -> assertThrows(IllegalStateException.class, () -> new MongoMigration(noHost)),
      () -> assertThrows(IllegalStateException.class, () -> new MongoMigration(noDatabase)));
  }

  @Test
  void aUsernameWithoutAPasswordAndAPasswordWithoutAUsernameStopTheStartup() {
    MockEnvironment onlyUsername = environment().withProperty("spring.mongodb.username", "pay");
    MockEnvironment onlyPassword = environment().withProperty("spring.mongodb.password", "secret");

    assertAll(
      () -> assertThrows(IllegalStateException.class, () -> new MongoMigration(onlyUsername)),
      () -> assertThrows(IllegalStateException.class, () -> new MongoMigration(onlyPassword)));
  }

  @Test
  void aFailureIsDescribedWithoutTheConnectionStringAndThePassword() {
    String url = MongoMigration.connectionUrl(
      "mongo.example", 27018, "payments_db", "pay", AWKWARD_PASSWORD, "admin");
    Exception failure = new IllegalStateException(
      String.format("cannot open %s", url),
      new IllegalArgumentException(String.format("rejected the password %s", AWKWARD_PASSWORD)));

    String description = MongoMigration.describeFailure(failure, url, AWKWARD_PASSWORD);

    assertAll(
      () -> assertFalse(description.contains(url)),
      () -> assertFalse(description.contains(AWKWARD_PASSWORD)),
      () -> assertFalse(description.contains("p%40ss")),
      () -> assertEquals(String.join(" <- ",
        "IllegalStateException: cannot open ***",
        "IllegalArgumentException: rejected the password ***"), description));
  }

  @Test
  void anUnreachableServerStopsTheStartupWithoutRevealingThePassword() {
    // Nothing listens on port 1: the driver gives up after the server selection timeout (5 s).
    MockEnvironment unreachable = new MockEnvironment()
      .withProperty("spring.mongodb.host", "localhost")
      .withProperty("spring.mongodb.port", "1")
      .withProperty("spring.mongodb.database", "payments_db")
      .withProperty("spring.mongodb.username", "pay")
      .withProperty("spring.mongodb.password", AWKWARD_PASSWORD);
    MongoMigration migration = new MongoMigration(unreachable);

    IllegalStateException thrown =
      assertThrows(IllegalStateException.class, migration::afterPropertiesSet);

    assertAll(
      () -> assertFalse(thrown.getMessage().contains(AWKWARD_PASSWORD)),
      () -> assertFalse(thrown.getMessage().contains("p%40ss")));
  }
}
