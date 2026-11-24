package com.innowise.paymentservice.config;

import java.util.Properties;

/**
 * The driver properties that {@link MongoMigration} gives to the Liquibase MongoDB extension.
 * They hold one value, {@code appName}, the name that the connection shows to MongoDB.
 *
 * <p>The value is there to keep the extension from working out a name of its own. Without it
 * {@code MongoConnection.getAppName} calls {@code getVersion}, which opens the manifest of the
 * jar of the extension with {@code new URL(classPath.substring(0, classPath.indexOf("!")) + ...)}.
 * In the executable jar of Spring Boot the URL of a class is
 * {@code jar:nested:/app/app.jar/!BOOT-INF/lib/liquibase-mongodb.jar!/...}, so the first
 * exclamation mark ends the path of the OUTER jar, the manifest URL has an empty nested entry,
 * and Spring Boot's loader refuses it with "nestedEntryName must not be empty" (reported as
 * liquibase-mongodb issue 612). On a plain class path (the IDE, the tests) the URL has one
 * exclamation mark and works, which is why only the container showed it.
 *
 * <p>Liquibase creates the class by name, so it must be public and have a public constructor
 * without arguments.
 */
public class MongoDriverProperties extends Properties {

  private static final long serialVersionUID = 1L;

  /** The name of the connection in MongoDB (the log of the server, the profiler). */
  public static final String APP_NAME = "payment-service-liquibase";

  public MongoDriverProperties() {
    setProperty("appName", APP_NAME);
  }
}
