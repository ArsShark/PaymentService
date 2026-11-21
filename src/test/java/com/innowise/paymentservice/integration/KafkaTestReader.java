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
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = newConsumer(bootstrapServers)) {
      assignAll(consumer, topic);

      Instant giveUpAt = Instant.now().plus(timeout);
      while (found.size() < expected && Instant.now().isBefore(giveUpAt)) {
        collect(consumer.poll(Duration.ofMillis(500)), key, found);
      }
    }
    return found;
  }

  /**
   * Reads everything that is in the topic NOW and returns the records with the given key. The end
   * of every partition is noted first and the reading stops when it is reached, so the answer is
   * complete for what was written before the call: it proves "exactly these records", and the
   * absence of a record, which {@link #readByKey} cannot. A record that is still in the buffer of
   * a producer is not in the topic yet: the test flushes the producer first.
   *
   * @param bootstrapServers the address of the broker
   * @param topic            the topic to read
   * @param key              the key of the wanted records
   * @param timeout          how long to wait at most for the end to be reached
   * @return the records with that key, in the order they were read (the order of their
   *   partition, and one key always lives in one partition)
   */
  static List<ConsumerRecord<String, String>> readAllByKey(String bootstrapServers, String topic,
    String key, Duration timeout) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = newConsumer(bootstrapServers)) {
      List<TopicPartition> partitions = assignAll(consumer, topic);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

      Instant giveUpAt = Instant.now().plus(timeout);
      while (!reached(consumer, ends) && Instant.now().isBefore(giveUpAt)) {
        collect(consumer.poll(Duration.ofMillis(500)), key, found);
      }
      if (!reached(consumer, ends)) {
        throw new IllegalStateException(
          String.format("The end of the topic %s was not reached in %s", topic, timeout));
      }
    }
    return found;
  }

  private static KafkaConsumer<String, String> newConsumer(String bootstrapServers) {
    Map<String, Object> config = Map.of(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new KafkaConsumer<>(config);
  }

  /** Assigns every partition of the topic by hand and goes to the beginning of each. */
  private static List<TopicPartition> assignAll(KafkaConsumer<String, String> consumer,
    String topic) {
    List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
      .map(info -> new TopicPartition(info.topic(), info.partition()))
      .toList();
    consumer.assign(partitions);
    consumer.seekToBeginning(partitions);
    return partitions;
  }

  private static void collect(Iterable<ConsumerRecord<String, String>> records, String key,
    List<ConsumerRecord<String, String>> found) {
    for (ConsumerRecord<String, String> record : records) {
      if (key.equals(record.key())) {
        found.add(record);
      }
    }
  }

  /** Whether the consumer has read up to the noted end of every partition. */
  private static boolean reached(KafkaConsumer<String, String> consumer,
    Map<TopicPartition, Long> ends) {
    return ends.entrySet().stream()
      .allMatch(end -> consumer.position(end.getKey()) >= end.getValue());
  }
}
