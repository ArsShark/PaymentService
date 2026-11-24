package com.innowise.paymentservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * Checks the configuration files without starting Spring: the {@code docker} profile gives a
 * container its addresses, every default can be overridden by an environment variable, and no
 * file holds a credential. A mistake in a property name would otherwise show only when the
 * container starts.
 *
 * <p>Two things differ from the profiles of the other services. The profile {@code local} reads
 * the {@code .env} file (so the IDE needs no environment variables) and {@code docker} must not;
 * and the address of the random number service is the same everywhere, so it is the one URL that
 * {@code application.yml} may hold.
 */
class SpringProfilesTest {

  private static final String DOCKER_FILE = "application-docker.yml";
  private static final String LOCAL_FILE = "application-local.yml";
  private static final String BASE_FILE = "application.yml";

  /** The key with which the profile {@code local} imports the {@code .env} file. */
  private static final String ENV_FILE_IMPORT = "spring.config.import";

  private static final String RANDOM_API_URI = "app.random-api.base-uri";

  @Test
  void dockerDefaultsAreTheServiceNamesOfTheComposeNetwork() {
    PropertySourcesPropertyResolver resolver = resolverFor(DOCKER_FILE, Map.of());

    assertAll(
      () -> assertEquals("mongodb", resolver.getProperty("spring.mongodb.host")),
      () -> assertEquals("27017", resolver.getProperty("spring.mongodb.port")),
      () -> assertEquals("payment_service_db",
        resolver.getProperty("spring.mongodb.database")),
      () -> assertEquals("kafka:19092",
        resolver.getProperty("spring.kafka.bootstrap-servers")),
      () -> assertEquals("http://authentication-service:8081/.well-known/jwks.json",
        resolver.getProperty("app.security.jwks-uri")));
  }

  @Test
  void localDefaultsAreTheContainersThatTheComposeFilePublishesOnTheHost() {
    PropertySourcesPropertyResolver resolver = resolverFor(LOCAL_FILE, Map.of());

    assertAll(
      () -> assertEquals("localhost", resolver.getProperty("spring.mongodb.host")),
      () -> assertEquals("27017", resolver.getProperty("spring.mongodb.port")),
      () -> assertEquals("payment_service_db",
        resolver.getProperty("spring.mongodb.database")),
      () -> assertEquals("localhost:9092",
        resolver.getProperty("spring.kafka.bootstrap-servers")),
      () -> assertEquals("http://localhost:8081/.well-known/jwks.json",
        resolver.getProperty("app.security.jwks-uri")));
  }

  @Test
  void environmentVariablesOverrideEveryDockerDefault() {
    PropertySourcesPropertyResolver resolver = resolverFor(DOCKER_FILE, overrides());

    assertAll(
      () -> assertEquals("db.internal", resolver.getProperty("spring.mongodb.host")),
      () -> assertEquals("27018", resolver.getProperty("spring.mongodb.port")),
      () -> assertEquals("payments", resolver.getProperty("spring.mongodb.database")),
      () -> assertEquals("broker.internal:9003",
        resolver.getProperty("spring.kafka.bootstrap-servers")),
      () -> assertEquals("http://auth.internal:9001/jwks",
        resolver.getProperty("app.security.jwks-uri")));
  }

  @Test
  void environmentVariablesOverrideEveryLocalDefault() {
    PropertySourcesPropertyResolver resolver = resolverFor(LOCAL_FILE, overrides());

    assertAll(
      () -> assertEquals("db.internal", resolver.getProperty("spring.mongodb.host")),
      () -> assertEquals("27018", resolver.getProperty("spring.mongodb.port")),
      () -> assertEquals("payments", resolver.getProperty("spring.mongodb.database")),
      () -> assertEquals("broker.internal:9003",
        resolver.getProperty("spring.kafka.bootstrap-servers")),
      () -> assertEquals("http://auth.internal:9001/jwks",
        resolver.getProperty("app.security.jwks-uri")));
  }

  @Test
  void dockerDefaultsPassTheChecksOfTheSettings() {
    PropertySourcesPropertyResolver docker = resolverFor(DOCKER_FILE, Map.of());
    PropertySourcesPropertyResolver base = resolverFor(BASE_FILE, Map.of());
    String jwksUri = docker.getProperty("app.security.jwks-uri");
    String randomApiUri = base.getProperty(RANDOM_API_URI);

    assertAll(
      () -> assertDoesNotThrow(
        () -> new SecurityProperties(jwksUri, "authentication-service")),
      () -> assertDoesNotThrow(() -> new RandomApiProperties(
        randomApiUri, Duration.ofSeconds(2), Duration.ofSeconds(5))));
  }

  @Test
  void localImportsTheEnvFileAndDockerDoesNot() {
    PropertySourcesPropertyResolver local = resolverFor(LOCAL_FILE, Map.of());

    assertAll(
      () -> assertEquals("optional:file:.env[.properties]", local.getProperty(ENV_FILE_IMPORT)),
      () -> assertFalse(keysOf(DOCKER_FILE).contains(ENV_FILE_IMPORT)));
  }

  @Test
  void bothProfilesDefineTheSameKeysExceptTheImportOfTheEnvFile() {
    Set<String> localKeys = keysOf(LOCAL_FILE);
    localKeys.remove(ENV_FILE_IMPORT);

    assertEquals(localKeys, keysOf(DOCKER_FILE));
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "spring.mongodb.host", "spring.mongodb.port", "spring.mongodb.database",
    "spring.kafka.bootstrap-servers", "app.security.jwks-uri"})
  void theBaseConfigurationHasNoAddressOfAnotherService(String key) {
    assertFalse(keysOf(BASE_FILE).contains(key), key);
  }

  @Test
  void theOnlyUrlOfTheBaseConfigurationIsTheRandomNumberService() {
    Set<String> keysWithUrl = new TreeSet<>();
    for (PropertySource<?> source : load(BASE_FILE)) {
      EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
      for (String key : enumerable.getPropertyNames()) {
        if (String.valueOf(enumerable.getProperty(key)).contains("://")) {
          keysWithUrl.add(key);
        }
      }
    }

    assertEquals(Set.of(RANDOM_API_URI), keysWithUrl);
  }

  @Test
  void theRandomNumberServiceIsRandomOrgUnlessTheEnvironmentSaysOtherwise() {
    PropertySourcesPropertyResolver byDefault = resolverFor(BASE_FILE, Map.of());
    PropertySourcesPropertyResolver replaced = resolverFor(BASE_FILE,
      Map.of("RANDOM_API_URI", "http://stand-in.internal:9000"));

    assertAll(
      () -> assertEquals("https://www.random.org", byDefault.getProperty(RANDOM_API_URI)),
      () -> assertEquals("http://stand-in.internal:9000", replaced.getProperty(RANDOM_API_URI)));
  }

  @Test
  void theCredentialsOfMongoDbAreOnlyPlaceholdersWithoutDefaults() {
    // Not resolved on purpose: the raw text shows that no value is written in the file.
    assertAll(
      () -> assertEquals("${MONGO_USERNAME}", rawValue(BASE_FILE, "spring.mongodb.username")),
      () -> assertEquals("${MONGO_PASSWORD}", rawValue(BASE_FILE, "spring.mongodb.password")));
  }

  @ParameterizedTest
  @ValueSource(strings = {DOCKER_FILE, LOCAL_FILE})
  void profileFilesHoldNoCredentials(String file) {
    Set<String> keys = keysOf(file);

    assertTrue(keys.stream().noneMatch(SpringProfilesTest::looksLikeACredential), keys::toString);
  }

  private static boolean looksLikeACredential(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.contains("password") || lower.contains("username") || lower.contains("secret")
      || lower.contains("api-key");
  }

  /** The environment variables that every default of a profile can be replaced with. */
  private static Map<String, Object> overrides() {
    return Map.of(
      "MONGO_HOST", "db.internal",
      "MONGO_PORT", "27018",
      "MONGO_DB", "payments",
      "KAFKA_BOOTSTRAP_SERVERS", "broker.internal:9003",
      "AUTH_JWKS_URI", "http://auth.internal:9001/jwks");
  }

  /** The values of the file, with the given environment variables in front of them. */
  private static PropertySourcesPropertyResolver resolverFor(
    String file, Map<String, Object> environment) {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("environment", new HashMap<>(environment)));
    for (PropertySource<?> source : load(file)) {
      sources.addLast(source);
    }
    return new PropertySourcesPropertyResolver(sources);
  }

  /** The text of one value as written in the file, placeholders not resolved. */
  private static String rawValue(String file, String key) {
    for (PropertySource<?> source : load(file)) {
      Object value = source.getProperty(key);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }

  private static Set<String> keysOf(String file) {
    Set<String> keys = new TreeSet<>();
    for (PropertySource<?> source : load(file)) {
      keys.addAll(List.of(((EnumerablePropertySource<?>) source).getPropertyNames()));
    }
    return keys;
  }

  private static List<PropertySource<?>> load(String file) {
    try {
      return new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
