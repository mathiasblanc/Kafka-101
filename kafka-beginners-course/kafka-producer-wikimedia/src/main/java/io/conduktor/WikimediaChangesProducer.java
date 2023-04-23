package io.conduktor;

import com.launchdarkly.eventsource.EventSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.net.URI;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class WikimediaChangesProducer {
    private static final String BOOTSTRAP_SERVER = "127.0.0.1:9092";
    private static final String TOPIC = "wikimedia.recentchange";
    private static final String URL = "https://stream.wikimedia.org/v2/stream/recentchange";

    public static void main(String[] args) throws InterruptedException {
        var props = new Properties();
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVER);
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32*1024));
        props.setProperty(ProducerConfig.LINGER_MS_CONFIG, "20");


        var producer = new KafkaProducer<String, String>(props);

        var eventHandler = new WikimediaChangeHandler(producer, TOPIC);
        var builder = new EventSource.Builder(eventHandler, URI.create(URL));
        var eventSource = builder.build();
        eventSource.start();

        TimeUnit.MINUTES.sleep(10);
    }
}