package com.innowise.paymentservice.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Applies the Liquibase changelog to MongoDB when the application starts.
 *
 * <p>Spring Boot has no Liquibase auto-configuration for MongoDB: {@code SpringLiquibase} works
 * through a JDBC {@code DataSource}. So this bean opens the database with the extension
 * {@code liquibase-mongodb} and runs {@code update} itself. It is an {@link InitializingBean},
 * so the changelog is applied while the context is built, before the web server accepts a
 * request; a failure stops the startup.
 *
 * <p>The connection settings are the ones of Spring Boot ({@code spring.mongodb.*}), so the
 * application and the migration always talk to the same database. The credentials go into the
 * connection string, URL-encoded. The string is never logged, and a failure message is scrubbed
 * of it and of the password (the messages of the driver and of Liquibase may quote them).
 */
@Component
public class MongoMigration implements InitializingBean {

  private static final Logger LOGGER = LoggerFactory.getLogger(MongoMigration.class);

  /** The changelog on the classpath; it includes the files of {@code changes/} in order. */
  static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

  private static final int DEFAULT_PORT = 27017;
  private static final String DEFAULT_AUTHENTICATION_DATABASE = "admin";
  private static final int CONNECT_TIMEOUT_MS = 2000;
  static final int DEFAULT_SERVER_SELECTION_TIMEOUT_MS = 5000;
  private static final int MAX_CAUSE_DEPTH = 10;

  /**
   * The class of the driver properties (with the application name): see
   * {@link MongoDriverProperties}, which says why the extension needs it in the container.
   */
  static final String DRIVER_PROPERTIES = MongoDriverProperties.class.getName();

  /** How many frames of the innermost cause the startup failure shows. */
  private static final int MAX_FRAMES = 15;
  private static final String MASK = "***";

  private final String host;
  private final int port;
  private final String database;
  private final String username;
  private final String password;
  private final String authenticationDatabase;
  private final int serverSelectionTimeoutMs;

  /**
   * Reads the connection settings of the application.
   *
   * @param environment the environment that holds {@code spring.mongodb.*}
   * @throws IllegalStateException if the host or the database is missing, or only one of the
   *     username and the password is set
   */
  @Autowired
  public MongoMigration(Environment environment) {
    this(environment, DEFAULT_SERVER_SELECTION_TIMEOUT_MS);
  }

  /**
   * Like {@link #MongoMigration(Environment)} with a chosen time to wait for the server; the
   * tests use a short one for a server that is not there.
   */
  MongoMigration(Environment environment, int serverSelectionTimeoutMs) {
    this.serverSelectionTimeoutMs = serverSelectionTimeoutMs;
    this.host = environment.getRequiredProperty("spring.mongodb.host");
    this.port = environment.getProperty("spring.mongodb.port", Integer.class, DEFAULT_PORT);
    this.database = environment.getRequiredProperty("spring.mongodb.database");
    this.username = environment.getProperty("spring.mongodb.username");
    this.password = environment.getProperty("spring.mongodb.password");
    this.authenticationDatabase = environment.getProperty(
      "spring.mongodb.authentication-database", DEFAULT_AUTHENTICATION_DATABASE);
    if (StringUtils.hasText(username) != StringUtils.hasText(password)) {
      throw new IllegalStateException(
        "The MongoDB username and password must be set together or not at all");
    }
  }

  @Override
  public void afterPropertiesSet() {
    LOGGER.info("Applying the MongoDB changelog {}", CHANGELOG);
    String url = connectionUrl(
      host, port, database, username, password, authenticationDatabase, serverSelectionTimeoutMs);
    try (ResourceAccessor accessor = resourceAccessor()) {
      // The accessor is also the one of the CURRENT SCOPE, as SpringLiquibase does: Liquibase
      // reads the scope for its own lookups, and the default of the scope is a scan of the class
      // path. A precaution for the executable jar of Spring Boot, not the cause of the failure
      // that was met there: see MongoDriverProperties.
      Scope.child(Scope.Attr.resourceAccessor, accessor, () -> {
        try (Database liquibaseDatabase =
               DatabaseFactory.getInstance()
                 .openDatabase(url, null, null, DRIVER_PROPERTIES, accessor);
             Liquibase liquibase = new Liquibase(CHANGELOG, accessor, liquibaseDatabase)) {
          liquibase.update(new Contexts(), new LabelExpression());
        }
      });
    } catch (Exception e) {
      throw new IllegalStateException(String.format(
        "Applying the MongoDB changelog failed: %s | where: %s",
        describeFailure(e, url, password), rootCauseFrames(e)));
    }
    LOGGER.info("The MongoDB changelog is applied");
  }

  /**
   * The accessor through which Liquibase reads the changelog: the resource loader of Spring (what
   * {@code SpringLiquibase} uses), not the class path scan of Liquibase
   * ({@code ClassLoaderResourceAccessor}), which has had trouble with the nested jars of the
   * executable jar of Spring Boot. A precaution: the failure that stopped the container was in
   * the MongoDB extension and not here (see {@link MongoDriverProperties}), so this choice is not
   * proved by that failure.
   */
  static ResourceAccessor resourceAccessor() {
    return new SpringResourceAccessor(
      new DefaultResourceLoader(MongoMigration.class.getClassLoader()));
  }

  /**
   * Builds the MongoDB connection string. The user name and the password are URL-encoded; the
   * driver decodes them. {@code authSource} is the database in which the user was created.
   */
  static String connectionUrl(String host, int port, String database, String username,
                              String password, String authenticationDatabase,
                              int serverSelectionTimeoutMs) {
    StringBuilder url = new StringBuilder("mongodb://");
    boolean withCredentials = StringUtils.hasText(username);
    if (withCredentials) {
      Objects.requireNonNull(password, "password");
      url.append(encode(username)).append(':').append(encode(password)).append('@');
    }
    url.append(host).append(':').append(port).append('/').append(database)
      .append("?connectTimeoutMS=").append(CONNECT_TIMEOUT_MS)
      .append("&serverSelectionTimeoutMS=").append(serverSelectionTimeoutMs);
    if (withCredentials) {
      url.append("&authSource=").append(encode(authenticationDatabase));
    }
    return url.toString();
  }

  /**
   * Describes a failure for the startup error: the class and the message of every exception of
   * the cause chain, with the connection string and the password replaced by asterisks. The
   * stack trace is dropped on purpose, because the exceptions are not copied.
   */
  static String describeFailure(Throwable failure, String url, String password) {
    List<String> secrets = StringUtils.hasText(password)
      ? List.of(url, encode(password), password) : List.of(url);
    StringBuilder description = new StringBuilder();
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (depth > 0) {
        description.append(" <- ");
      }
      String message = String.valueOf(current.getMessage());
      for (String secret : secrets) {
        message = message.replace(secret, MASK);
      }
      description.append(current.getClass().getSimpleName()).append(": ").append(message);
      current = current.getCause() == current ? null : current.getCause();
    }
    return description.toString();
  }

  /**
   * The first frames of the innermost cause, as {@code class.method(file:line)}. The messages of
   * the exceptions can hold the connection string or the password and are scrubbed by
   * {@link #describeFailure}; the frames hold only names of code, so they are safe, and without
   * them a failure inside a library (for example in the executable jar of Spring Boot) cannot be
   * located: the stack trace is not logged on purpose.
   */
  static String rootCauseFrames(Throwable failure) {
    Throwable root = failure;
    for (int depth = 0; root.getCause() != null && root.getCause() != root
         && depth < MAX_CAUSE_DEPTH; depth++) {
      root = root.getCause();
    }
    StringBuilder frames = new StringBuilder();
    StackTraceElement[] trace = root.getStackTrace();
    for (int i = 0; i < Math.min(trace.length, MAX_FRAMES); i++) {
      if (i > 0) {
        frames.append("; ");
      }
      frames.append(trace[i]);
    }
    return frames.toString();
  }

  /**
   * URL-encodes a part of the user info. {@link URLEncoder} follows the rules of HTML forms and
   * writes a space as a plus sign, but the MongoDB driver does not turn a plus sign in the user
   * info back into a space (it would log in as "pay+user"), so the space is written as %20.
   * A real plus sign is already encoded as %2B.
   */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
