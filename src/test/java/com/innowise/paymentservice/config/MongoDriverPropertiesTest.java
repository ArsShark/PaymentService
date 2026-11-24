package com.innowise.paymentservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.function.Supplier;
import liquibase.ext.mongodb.database.MongoConnection;
import org.junit.jupiter.api.Test;

/**
 * Pins what the container needs from the Liquibase MongoDB extension. In the executable jar of
 * Spring Boot, {@code MongoConnection.getVersion()} fails with "nestedEntryName must not be
 * empty"; the extension reaches it only when the driver properties carry no {@code appName}. A
 * test on a plain class path cannot reproduce the failure (there the version lookup works), so
 * these tests pin the facts that the fix stands on: the properties give the name, Liquibase can
 * create the class by its name, and the extension then never asks for the version. The run of
 * the container is the proof.
 */
class MongoDriverPropertiesTest {

  @Test
  void thePropertiesCarryTheApplicationName() {
    assertEquals(MongoDriverProperties.APP_NAME,
      new MongoDriverProperties().getProperty("appName"));
  }

  @Test
  void liquibaseCanCreateTheClassByItsName() throws Exception {
    Class<?> type = Class.forName(MongoMigration.DRIVER_PROPERTIES);

    Object created = type.getConstructor().newInstance();

    assertAll(
      () -> assertEquals(MongoDriverProperties.class, type),
      () -> assertTrue(Modifier.isPublic(type.getModifiers())),
      () -> assertInstanceOf(Properties.class, created),
      () -> assertEquals(MongoDriverProperties.APP_NAME,
        ((Properties) created).getProperty("appName")));
  }

  @Test
  void theExtensionUsesTheNameAndNeverAsksForTheVersion() {
    Probe probe = new Probe(() -> {
      throw new AssertionError("getVersion must not be called when appName is set");
    });

    assertEquals(MongoDriverProperties.APP_NAME, probe.appName(new MongoDriverProperties()));
  }

  @Test
  void withoutTheNameTheExtensionAsksForTheVersion() {
    Probe probe = new Probe(() -> "9.9.9");

    assertTrue(probe.appName(new Properties()).endsWith("9.9.9"));
  }

  /**
   * An extension connection with a version that the test controls. {@code getAppName} is
   * protected, so the probe, a subclass, exposes it.
   */
  private static final class Probe extends MongoConnection {

    private final Supplier<String> version;

    Probe(Supplier<String> version) {
      this.version = version;
    }

    @Override
    protected String getVersion() {
      return version.get();
    }

    String appName(Properties properties) {
      return getAppName(properties, false);
    }
  }
}
