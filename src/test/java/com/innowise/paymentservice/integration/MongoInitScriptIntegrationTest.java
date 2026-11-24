package com.innowise.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.config.MongoMigration;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the script that creates the MongoDB user of the application
 * ({@code docker/mongodb/01-create-app-user.js}) on a real, EMPTY container, the way
 * {@code docker-compose.yml} starts it: the image creates the administrator, runs the script from
 * {@code /docker-entrypoint-initdb.d} once, and restarts.
 *
 * <p>Unlike the other integration tests this class starts its own container and no Spring
 * context. The shared container of the other tests was started without the script, and the
 * script runs only on the first start of an empty data directory, so it cannot be tested there.
 * What matters is that the least-privilege user (readWrite plus collMod) can do what the
 * application does at startup (the Liquibase changelog creates the collection with its validator
 * and its indexes, and Liquibase puts a validator on its own history collection) and nothing
 * more. The tests of the application itself use the administrator and cannot show a missing
 * right: the first run of this test found that readWrite alone is not enough.
 */
@Tag("integration")
class MongoInitScriptIntegrationTest {

  private static final String SCRIPT = "docker/mongodb/01-create-app-user.js";
  private static final String DATABASE = "init_script_test_db";
  private static final int PORT = 27017;

  /** MongoDB answers a command that the user may not run with the error code Unauthorized. */
  private static final int UNAUTHORIZED = 13;

  private static final String ROOT_USERNAME = "root-user";
  private static final String ROOT_PASSWORD = "root-password";
  private static final String APP_USERNAME = "payment-app";

  /**
   * A password that breaks a shell, a JavaScript string and a connection string: a dollar sign,
   * quotes, a backslash, a hash, a space, a percent sign and a plus.
   */
  private static final String APP_PASSWORD = "p@ss:w/rd+1% x$HOME#'\"\\";

  private static GenericContainer<?> mongo;

  @BeforeAll
  static void startAnEmptyContainerWithTheScript() {
    // With a root user the image starts a temporary server, runs the scripts, stops it and starts
    // the real server, so "Waiting for connections" is logged twice.
    mongo = new GenericContainer<>(DockerImageName.parse("mongo:8"))
      .withExposedPorts(PORT)
      .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USERNAME)
      .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
      .withEnv("MONGO_APP_USERNAME", APP_USERNAME)
      .withEnv("MONGO_APP_PASSWORD", APP_PASSWORD)
      .withEnv("MONGO_APP_DATABASE", DATABASE)
      .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT),
        "/docker-entrypoint-initdb.d/01-create-app-user.js")
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 2));
    mongo.start();
  }

  @AfterAll
  static void stopTheContainer() {
    mongo.stop();
  }

  @Test
  void theUserOfTheApplicationCanApplyTheChangelog() {
    MockEnvironment environment = new MockEnvironment()
      .withProperty("spring.mongodb.host", mongo.getHost())
      .withProperty("spring.mongodb.port", String.valueOf(mongo.getMappedPort(PORT)))
      .withProperty("spring.mongodb.database", DATABASE)
      .withProperty("spring.mongodb.username", APP_USERNAME)
      .withProperty("spring.mongodb.password", APP_PASSWORD)
      .withProperty("spring.mongodb.authentication-database", "admin");

    new MongoMigration(environment).afterPropertiesSet();

    try (MongoClient client = clientOf(APP_USERNAME, APP_PASSWORD, "admin")) {
      MongoDatabase database = client.getDatabase(DATABASE);
      List<String> collections = database.listCollectionNames().into(new ArrayList<>());
      List<String> indexNames = new ArrayList<>();
      database.getCollection("payments").listIndexes()
        .forEach(index -> indexNames.add(index.getString("name")));
      assertAll(
        () -> assertTrue(collections.contains("payments"), collections::toString),
        // The index of _id and the four of the changelog.
        () -> assertEquals(5, indexNames.size(), indexNames::toString),
        () -> assertTrue(indexNames.contains("uq_payments_order_id_success")),
        // The history of Liquibase is written by the same user.
        () -> assertEquals(2, database.getCollection("DATABASECHANGELOG").countDocuments()));
    }
  }

  @Test
  void theUserCannotUseAnotherDatabaseCreateUsersOrDropItsOwnDatabase() {
    try (MongoClient client = clientOf(APP_USERNAME, APP_PASSWORD, "admin")) {
      assertAll(
        () -> assertEquals(UNAUTHORIZED, assertThrows(MongoException.class,
          () -> client.getDatabase("another_db").getCollection("things")
            .insertOne(new Document("a", 1))).getCode()),
        () -> assertEquals(UNAUTHORIZED, assertThrows(MongoException.class,
          () -> client.getDatabase("admin").runCommand(new Document("createUser", "intruder")
            .append("pwd", "intruder-password").append("roles", List.of()))).getCode()),
        () -> assertEquals(UNAUTHORIZED, assertThrows(MongoException.class,
          () -> client.getDatabase(DATABASE).runCommand(new Document("dropDatabase", 1)))
          .getCode()));
    }
  }

  private static MongoClient clientOf(String username, String password, String authDatabase) {
    MongoCredential credential =
      MongoCredential.createCredential(username, authDatabase, password.toCharArray());
    MongoClientSettings settings = MongoClientSettings.builder()
      .applyToClusterSettings(cluster -> cluster
        .hosts(List.of(new ServerAddress(mongo.getHost(), mongo.getMappedPort(PORT))))
        .serverSelectionTimeout(10, TimeUnit.SECONDS))
      .credential(credential)
      .build();
    return MongoClients.create(settings);
  }
}
