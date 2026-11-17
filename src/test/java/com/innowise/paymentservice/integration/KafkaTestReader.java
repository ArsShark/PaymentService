package com.innowise.paymentservice.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads a topic the plain way, with the Kafka client and no Spring, so a test sees exactly what
 * another service would see on the wire: the key, the headers and the raw text of the value.
 *
 * <p>It does not use a consumer group (the partitions are assigned by hand and nothing is
 * committed), so every call reads the topic from the beginning and cannot disturb another test.
 * The topic is shared by all tests of the context, so a test looks for the records of a key that
 * only it uses.
 */
final class KafkaTestReader {

  private KafkaTestReader() {
  }

  /**
   * Reads the topic until {@code expected} records with the given key were found or the time is
   * over.
   *
   * @param bootstrapServers the address of the broker
   * @param topic            the topic to read
   * @param key              the key of the wanted records
   * @param expected         how many records to wait for
   * @param timeout          how long to wait at most
   * @return the records with that key, in the order they were read (the order of their
   *   partition); fewer than expected if the time ran out
   */
  static List<ConsumerRecord<String, String>> readByKey(String bootstrapServers, String topic,
    String key, int expected, Duration timeout) {
    Map<String, Object> config = Map.of(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
        .map(info -> new TopicPartition(info.topic(), info.partition()))
        .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);

      Instant giveUpAt = Instant.now().plus(timeout);
      while (found.size() < expected && Instant.now().isBefore(giveUpAt)) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (key.equals(record.key())) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }
}
