package com.innowise.paymentservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a MongoDB container for the tests and tells the application where it is.
 *
 * <p>The container is a standalone server with authentication, like the one of
 * {@code docker-compose.yml}, so the tests use the same credentials path as the real
 * application. Spring manages its lifecycle (it is a bean). The address is given to the
 * application through {@code spring.mongodb.*}, the same properties that production uses; both
 * the MongoDB client of Spring Data and {@code MongoMigration} read them. So the tests need no
 * environment variables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /** The database of the tests; the application creates it on the first write. */
  public static final String DATABASE = "payment_test_db";

  private static final int MONGO_PORT = 27017;
  private static final String USERNAME = "payment-test";
  private static final String PASSWORD = "payment-test-password";

  /**
   * A MongoDB container with the same major version as the local one.
   *
   * <p>With a root user the image starts a temporary server to create the user, stops it and
   * starts the real one, so "Waiting for connections" is logged twice.
   *
   * @return the container
   */
  @Bean
  GenericContainer<?> mongoContainer() {
    return new GenericContainer<>(DockerImageName.parse("mongo:8"))
      .withExposedPorts(MONGO_PORT)
      .withEnv("MONGO_INITDB_ROOT_USERNAME", USERNAME)
      .withEnv("MONGO_INITDB_ROOT_PASSWORD", PASSWORD)
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 2));
  }

  /**
   * Gives the application the address and the credentials of the container.
   *
   * @param mongoContainer the started container
   * @return the registrar that Spring calls when it builds the environment
   */
  @Bean
  DynamicPropertyRegistrar mongoProperties(GenericContainer<?> mongoContainer) {
    return registry -> {
      registry.add("spring.mongodb.host", mongoContainer::getHost);
      registry.add("spring.mongodb.port", () -> mongoContainer.getMappedPort(MONGO_PORT));
      registry.add("spring.mongodb.database", () -> DATABASE);
      registry.add("spring.mongodb.username", () -> USERNAME);
      registry.add("spring.mongodb.password", () -> PASSWORD);
      registry.add("spring.mongodb.authentication-database", () -> "admin");
    };
  }
}
