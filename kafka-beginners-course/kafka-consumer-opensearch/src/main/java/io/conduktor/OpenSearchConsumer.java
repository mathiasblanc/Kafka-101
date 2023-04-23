package io.conduktor;

import com.google.gson.JsonParser;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

public class OpenSearchConsumer {
    private static RestHighLevelClient createOpenSearchClient() {
        final String connString = "https://ff2aqd7e7e:3iuy7bkxmz@fff-search-288783161.eu-central-1.bonsaisearch.net:443";

        // we build a URI from the connection string
        RestHighLevelClient restHighLevelClient;
        URI connUri = URI.create(connString);
        // extract login information if it exists
        String userInfo = connUri.getUserInfo();

        if (userInfo == null) {
            // REST client without security
            restHighLevelClient = new RestHighLevelClient(RestClient.builder(new HttpHost(connUri.getHost(), connUri.getPort(), "http")));
        } else {
            // REST client with security
            String[] auth = userInfo.split(":");

            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(auth[0], auth[1]));

            restHighLevelClient = new RestHighLevelClient(
                    RestClient.builder(new HttpHost(connUri.getHost(), connUri.getPort(), connUri.getScheme()))
                            .setHttpClientConfigCallback(
                                    httpAsyncClientBuilder -> httpAsyncClientBuilder.setDefaultCredentialsProvider(cp)
                                            .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())));
        }

        return restHighLevelClient;
    }

    private static KafkaConsumer<String, String> createConsumer() {
        final var groupId = "consumer-opensearch-demo";

        var props = new Properties();
        props.setProperty("bootstrap.servers", "127.0.0.1:9092");
        props.setProperty("key.deserializer", StringDeserializer.class.getName());
        props.setProperty("value.deserializer", StringDeserializer.class.getName());
        props.setProperty("group.id", groupId);
        props.setProperty("auto.offset.reset", "latest");
        props.setProperty("enable.auto.commit", "false");

        return new KafkaConsumer<>(props);
    }

    private static String extractId(String json) {
        return JsonParser.parseString(json)
                .getAsJsonObject()
                .get("meta")
                .getAsJsonObject()
                .get("id")
                .getAsString();
    }

    public static void main(String[] args) throws IOException {
        final var openSearchIndex = "wikimedia";
        Logger log = LoggerFactory.getLogger(OpenSearchConsumer.class);
        var openSearchClient = createOpenSearchClient();
        var consumer = createConsumer();
        var mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Detected a shutdown");
            consumer.wakeup();

            try {
                mainThread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));


        try (openSearchClient; consumer) {
            var indexExists = openSearchClient.indices().exists(new GetIndexRequest(openSearchIndex), RequestOptions.DEFAULT);

            if (!indexExists) {
                var createIndexRequest = new CreateIndexRequest(openSearchIndex);
                openSearchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                log.info("{} index created", openSearchIndex);
            } else {
                log.info("{} index already exists", openSearchIndex);
            }

            consumer.subscribe(Collections.singleton("wikimedia.recentchange"));

            while (true) {
                var records = consumer.poll(Duration.ofMillis(3000));
                int recordCount = records.count();
                log.info("Received {} records", recordCount);

                var bulkRequest = new BulkRequest();

                for (var record : records) {
                    try {
                        var id = extractId(record.value());
                        var indexRequest = new IndexRequest(openSearchIndex)
                                .source(record.value(), XContentType.JSON)
                                .id(id);

                        bulkRequest.add(indexRequest);
                    } catch (Exception e) {

                    }
                }

                if (bulkRequest.numberOfActions() > 0) {
                    var bulkResponse = openSearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
                    log.info("Inserted {} records", bulkResponse.getItems().length);

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    consumer.commitSync();
                    log.info("Offsets have been committed");
                }
            }
        } catch (WakeupException e) {
            log.info("Consumer is starting to shut down");
        } catch (Exception e) {
            log.error("Unexpected exception", e);
        } finally {
            consumer.close();
            openSearchClient.close();
            log.info("The consumer is gracefully shutdown");
        }
    }
}