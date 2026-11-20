package com.innowise.paymentservice.integration;

import com.innowise.paymentservice.JwksTestServer;
import com.innowise.paymentservice.TestKeys;
import com.innowise.paymentservice.TestTokens;
import com.innowise.paymentservice.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Base class of the integration tests: the whole application on a real MongoDB container and a
 * real Kafka broker.
 *
 * <p>All subclasses share one Spring context and with it one container, because the container is
 * a bean of the context and a second context would start a second one. So subclasses must not
 * change the context: no {@code @MockitoBean}, {@code @TestPropertySource},
 * {@code @DirtiesContext} or extra {@code @Import}. Properties that every test needs belong to
 * this class.
 *
 * <p>The tokens are verified the real way. A small HTTP server plays the key set endpoint of the
 * Authentication Service, and the application reads its address from {@code app.security.jwks-uri}
 * like in production. {@link #mockMvc} sends the token of an admin with every request; a test
 * that needs another caller sets its own {@code Authorization} header, which wins over this
 * default (see {@code TestSecurityConfiguration}). The clock of the application is a
 * {@link MutableClock} that {@link #resetState()} sets back to {@link #START}.
 *
 * <p>The tests do not use a transaction (MongoDB needs a replica set for one, and the service
 * does not use any). {@link #resetState()} removes the documents before every test, but keeps
 * the collection, its validator and its indexes: they are what the tests are about. The topic of
 * Kafka is never emptied: a test that reads it looks for the records of its own key.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class,
  TestSecurityConfiguration.class})
abstract class AbstractIntegrationTest {

  /** The instant the test clock shows at the start of every test: the {@code iat} of the tokens. */
  protected static final Instant START = TestTokens.ISSUED_AT;

  /**
   * The token that {@link #mockMvc} sends by default: an admin without a profile, valid for ten
   * years, so that a test that moves the clock far ahead does not lose its caller.
   */
  protected static final String ADMIN_TOKEN = TestTokens.admin()
    .expiresAt(TestTokens.ISSUED_AT.plus(Duration.ofDays(3650)))
    .build();

  /** The collection of the payments. */
  protected static final String PAYMENTS = "payments";

  /**
   * The key set endpoint of the Authentication Service. It is never stopped: its threads are
   * daemons, so they do not keep the JVM alive.
   */
  private static final JwksTestServer JWKS_SERVER = JwksTestServer.start(TestKeys.jwksJson());

  /** Gives the context the address of the key set that plays the Authentication Service. */
  @DynamicPropertySource
  static void keySetAddress(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwks-uri", JWKS_SERVER::jwksUri);
  }

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected MutableClock clock;

  @Autowired
  protected MongoTemplate mongoTemplate;

  /** The address of the Kafka container, for tests that talk to the broker themselves. */
  @Value("${spring.kafka.bootstrap-servers}")
  protected String kafkaBootstrapServers;

  /** Empties the collection of the payments and sets the clock to {@link #START}. */
  @BeforeEach
  protected void resetState() {
    mongoTemplate.getCollection(PAYMENTS).deleteMany(new Document());
    clock.set(START);
  }

  /**
   * The value of the {@code Authorization} header for a token.
   *
   * @param token a compact serialized token
   * @return the header value
   */
  protected static String bearer(String token) {
    return String.format("Bearer %s", token);
  }

  /** Matches an error response in the RFC 9457 format, {@code application/problem+json}. */
  protected static ResultMatcher problemJson() {
    return MockMvcResultMatchers.content()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }
}
