package com.innowise.paymentservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Checks the Compose files without Docker.
 *
 * <p>The init script of MongoDB is passed to the container as an inline config, not as a mounted
 * file: a bind mount needs the drive of the project to be visible to the Docker daemon, and with
 * Docker Desktop on Windows a USB drive is not, so the container got an empty DIRECTORY at the
 * path of the script and the image stopped with {@code EISDIR}. Testcontainers copies the file
 * through the API and never showed it. The text of the inline config is a copy of the script of
 * the repository, so these tests keep the two equal. They also keep a dollar sign out of the
 * script, because Compose interpolates variables in the text of an inline config (a template
 * literal of JavaScript would silently become an empty string).
 */
class DockerComposeFilesTest {

  private static final Path SCRIPT = Path.of("docker/mongodb/01-create-app-user.js");
  private static final String COMPOSE_FILE = "docker-compose.yml";
  private static final String DEV_COMPOSE_FILE = "docker-compose.dev.yml";

  @Test
  void theInitScriptOfTheComposeFileIsTheScriptOfTheRepository() throws IOException {
    Object content = propertiesOf(COMPOSE_FILE).get("configs.mongo_init_script.content");
    String inCompose = String.valueOf(content);
    String inRepository = Files.readString(SCRIPT);

    assertEquals(normalized(inRepository), normalized(inCompose));
  }

  @Test
  void theInitScriptHasNoDollarSign() throws IOException {
    String script = Files.readString(SCRIPT);

    assertFalse(script.contains("$"), "Compose would interpolate it in the inline config");
  }

  @Test
  void mongoDbGetsTheInitScriptAsAConfigInTheDirectoryOfTheImage() {
    Map<String, Object> properties = propertiesOf(COMPOSE_FILE);

    assertAll(
      () -> assertEquals("mongo_init_script",
        properties.get("services.mongodb.configs[0].source")),
      () -> assertEquals("/docker-entrypoint-initdb.d/01-create-app-user.js",
        properties.get("services.mongodb.configs[0].target")));
  }

  @ParameterizedTest
  @ValueSource(strings = {COMPOSE_FILE, DEV_COMPOSE_FILE})
  void noServiceMountsAPathOfTheHost(String file) {
    Map<String, Object> properties = propertiesOf(file);
    Map<String, Object> volumes = new TreeMap<>();
    properties.forEach((key, value) -> {
      if (key.matches("services\\.[^.]+\\.volumes\\[\\d+]")) {
        volumes.put(key, value);
      }
    });

    // A named volume is "name:/path": a name of two or more characters, so a drive letter such as
    // "F:" and a relative path such as "./docker" do not pass.
    assertTrue(volumes.values().stream()
        .allMatch(value -> String.valueOf(value).matches("[A-Za-z0-9][A-Za-z0-9_-]+:/.*")),
      volumes::toString);
  }

  /** Line endings and the final newline do not matter: YAML keeps one, a checkout may add more. */
  private static String normalized(String text) {
    return text.replace("\r\n", "\n").strip();
  }

  private static Map<String, Object> propertiesOf(String file) {
    try {
      Map<String, Object> properties = new TreeMap<>();
      for (PropertySource<?> source
        : new YamlPropertySourceLoader().load(file, new FileSystemResource(file))) {
        EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
        for (String key : enumerable.getPropertyNames()) {
          properties.put(key, enumerable.getProperty(key));
        }
      }
      return properties;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
