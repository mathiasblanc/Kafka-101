package io.conduktor;

import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.MessageEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WikimediaChangeHandler implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(WikimediaChangeHandler.class.getName());

    private final KafkaProducer<String, String> kafkaProducer;
    private final String TOPIC;

    public WikimediaChangeHandler(KafkaProducer<String, String> kafkaProducer, String topic) {
        this.kafkaProducer = kafkaProducer;
        TOPIC = topic;
    }

    @Override
    public void onOpen() {
        //nothing
    }

    @Override
    public void onClosed() {
        kafkaProducer.close();
    }

    @Override
    public void onMessage(String event, MessageEvent messageEvent) {
        log.info(messageEvent.getData());
        kafkaProducer.send(new ProducerRecord<>(TOPIC, messageEvent.getData()));
    }

    @Override
    public void onComment(String comment) {
        //nothing
    }

    @Override
    public void onError(Throwable t) {
        log.error("Error reading stream", t);
    }
}
