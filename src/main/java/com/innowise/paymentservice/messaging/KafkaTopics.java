package com.innowise.paymentservice.messaging;

/**
 * Names and sizes of the Kafka topics of the system. Constants, not settings: the topic is part
 * of the contract with the Order Service, and a name that an environment variable could change
 * would let the two services drift apart.
 */
public final class KafkaTopics {

  /** The topic of the payment events. This service writes it, the Order Service reads it. */
  public static final String PAYMENT_EVENTS = "payment-events";

  /**
   * Partitions of the topic. A record goes to the partition of the hash of its key, so all events
   * of one order stay together and in order; three partitions let up to three instances of the
   * Order Service read in parallel.
   */
  public static final int PAYMENT_EVENTS_PARTITIONS = 3;

  /** Copies of every partition. One broker cannot hold more than one. */
  public static final short REPLICAS = 1;

  private KafkaTopics() {
  }
}
