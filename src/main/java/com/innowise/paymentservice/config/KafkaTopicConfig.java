package com.innowise.paymentservice.config;

import com.innowise.paymentservice.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics that this service owns. {@code KafkaAdmin} of Spring Boot creates every
 * {@link NewTopic} bean when the application starts and does nothing if the topic already exists
 * (it only adds partitions if the declared number is larger).
 *
 * <p>The broker has automatic topic creation switched off, so a typo in a topic name is an error
 * and not a new topic. If the broker is not reachable at start, the application still starts and
 * logs a warning; the topic is then missing until the next start.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

  /**
   * The topic {@code payment-events}.
   *
   * @return the declaration of the topic
   */
  @Bean
  NewTopic paymentEventsTopic() {
    return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS)
      .partitions(KafkaTopics.PAYMENT_EVENTS_PARTITIONS)
      .replicas(KafkaTopics.REPLICAS)
      .build();
  }
}
