package io.conduktor.demos.kafka;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

public class ConsumerDemoWithShutdown {
    private static final Logger log = LoggerFactory.getLogger(ConsumerDemoWithShutdown.class);

    public static void main(String[] args) {
        var groupId = "my-java-application";
        var topic = "demo_java";

        var props = new Properties();
        props.setProperty("security.protocol", "SASL_SSL");
        props.setProperty("sasl.mechanism", "PLAIN");
        props.setProperty("bootstrap.servers", "cluster.playground.cdkt.io:9092");
        props.setProperty("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username='4o96P6rMj39rgrdG0X8eJt' password='eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJodHRwczovL2F1dGguY29uZHVrdG9yLmlvIiwic291cmNlQXBwbGljYXRpb24iOiJhZG1pbiIsInVzZXJNYWlsIjpudWxsLCJwYXlsb2FkIjp7InZhbGlkRm9yVXNlcm5hbWUiOiI0bzk2UDZyTWozOXJncmRHMFg4ZUp0Iiwib3JnYW5pemF0aW9uSWQiOjcyMzkwLCJ1c2VySWQiOjg0MDU3LCJmb3JFeHBpcmF0aW9uQ2hlY2siOiI4MGM5MzUyYS1iYmZlLTRmOGUtODU0MC1lM2IyZmQ0Y2M5ZjUifX0.cufYYVpQgSdcsAVaJPtMSKQYme8FZ7ZMgawtfZ5rVt8';");

        props.setProperty("key.deserializer", StringDeserializer.class.getName());
        props.setProperty("value.deserializer", StringDeserializer.class.getName());
        props.setProperty("group.id", groupId);
        props.setProperty("auto.offset.reset", "earliest");

        var consumer = new KafkaConsumer<String, String>(props);

        var mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                log.info("Detected a shutdown");
                consumer.wakeup();

                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        consumer.subscribe(List.of(topic));

        try {
            while (true) {
                var records = consumer.poll(Duration.ofMillis(1000));

                for (var record : records) {
                    log.info("Key: {}, value: {}", record.key(), record.value());
                    log.info("Partition: {}, offset: {}", record.partition(), record.offset());
                }
            }
        } catch (WakeupException e) {
            log.info("Consumer is starting to shutdown");
        } catch (Exception e) {
            log.error("Unexpected error", e);
        } finally {
            consumer.close();
            log.info("Consumer is now gracefully shutdown");
        }
    }
}