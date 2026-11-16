package com.innowise.paymentservice.integration;

import com.innowise.paymentservice.TestcontainersConfiguration;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Base class of the integration tests: the whole application on a real MongoDB container and a
 * real Kafka broker.
 *
 * <p>All subclasses share one Spring context and with it one container, because the container is
 * a bean of the context and a second context would start a second one. So subclasses must not
 * change the context: no {@code @MockitoBean}, {@code @TestPropertySource},
 * {@code @DirtiesContext} or extra {@code @Import}.
 *
 * <p>The tests do not use a transaction (MongoDB needs a replica set for one, and the service
 * does not use any). {@link #resetState()} removes the documents before every test, but keeps
 * the collection, its validator and its indexes: they are what the tests are about. The topic of
 * Kafka is never emptied: a test that reads it looks for the records of its own key.
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class AbstractIntegrationTest {

  /** The collection of the payments. */
  protected static final String PAYMENTS = "payments";

  @Autowired
  protected MongoTemplate mongoTemplate;

  /** The address of the Kafka container, for tests that talk to the broker themselves. */
  @Value("${spring.kafka.bootstrap-servers}")
  protected String kafkaBootstrapServers;

  /** Empties the collection of the payments. */
  @BeforeEach
  protected void resetState() {
    mongoTemplate.getCollection(PAYMENTS).deleteMany(new Document());
  }
}
