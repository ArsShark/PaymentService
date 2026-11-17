package com.innowise.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.paymentservice.messaging.KafkaTopics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;

/**
 * Proves that the application creates the topic it owns when it starts: {@code KafkaAdmin} reads
 * the {@code NewTopic} bean of {@code KafkaTopicConfig} and the broker has the topic with the
 * declared shape. The same check also shows that the Kafka settings of the application reach a
 * real broker.
 */
class KafkaTopicIntegrationTest extends AbstractIntegrationTest {

  @Test
  void thePaymentEventsTopicHasThreePartitionsAndOneReplicaOfEach() throws Exception {
    Map<String, Object> config = Map.of(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);

    TopicDescription topic;
    try (Admin admin = Admin.create(config)) {
      topic = admin.describeTopics(List.of(KafkaTopics.PAYMENT_EVENTS))
        .allTopicNames().get(10, TimeUnit.SECONDS).get(KafkaTopics.PAYMENT_EVENTS);
    }

    assertAll(
      () -> assertEquals(KafkaTopics.PAYMENT_EVENTS, topic.name()),
      () -> assertEquals(3, topic.partitions().size()),
      () -> assertEquals(List.of(1, 1, 1), topic.partitions().stream()
        .map(partition -> partition.replicas().size()).toList()));
  }
}
