package hu.dpc.phee.operator.config;

import hu.dpc.phee.operator.importer.RecordParser;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EnableKafka
@Configuration
public class KafkaConfiguration {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${kafka.brokers}")
    private String kafkaBrokers;

    @Value("${kafka.consumer-group}")
    private String consumerGroup;

    @Value("${kafka.concurrency}")
    private int concurrency;

    @Value("${kafka.poll-timeout-ms:3000}")
    private long pollTimeoutMs;

    @Value("${kafka.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    @Value("${kafka.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${kafka.max-poll-records:100}")
    private int maxPollRecords;

    @Value("${kafka.shutdown-timeout-ms:45000}")
    private long shutdownTimeoutMs;

    @Autowired
    private RecordParser recordParser;

    @Bean
    KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setPollTimeout(pollTimeoutMs);
        // Bounds a direct container stop(); the context-close path is bounded by lifecycleProcessor().
        factory.getContainerProperties().setShutdownTimeout(shutdownTimeoutMs);
        // Commit each record's offset as soon as the record is processed. On SIGTERM the consumer
        // finishes its current poll, and when the database is slow that can outlast the shutdown
        // budget; with per-batch commits every record already written in that poll was then read
        // again. Per-record commits cap the redelivery at the records in flight, whatever the
        // database speed, for one synchronous commit per record (FD-1939).
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // A record that failed on a database error is re-polled instead of being committed past.
        // Only DataAccessException and RetryableRecordException reach this handler (KafkaConsumer
        // logs and swallows the rest), so retrying without limit stalls the partition during a
        // database outage or while a routing mapping is missing instead of skipping records; the
        // lag shows up on the KEDA scaler.
        factory.setErrorHandler(new SeekToCurrentErrorHandler(new FixedBackOff(retryBackoffMs, FixedBackOff.UNLIMITED_ATTEMPTS)));
        // Entities cached in memory belong to the partitions this pod owns. Drop them when
        // partitions move so the next event re-reads the current row from the database.
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                logger.info("partitions revoked {}, clearing in-flight state", partitions);
                recordParser.clearInflightState();
            }
        });
        return factory;
    }

    /**
     * On SIGTERM Spring stops the listener containers first and waits for their consumer threads to
     * finish the records of the current poll, commit the offsets and leave the group. The wait is the
     * lifecycle processor's timeout per shutdown phase (30 s by default, and not configurable through a
     * property on this Spring Boot version), after which the data source is closed under the still
     * running batch and the JVM exits. Both this and the container shutdown timeout follow
     * kafka.shutdown-timeout-ms, which must stay below the pod's terminationGracePeriodSeconds.
     */
    @Bean(name = "lifecycleProcessor")
    public DefaultLifecycleProcessor lifecycleProcessor() {
        DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
        processor.setTimeoutPerShutdownPhase(shutdownTimeoutMs);
        return processor;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfigs());
    }

    @Bean
    public Map<String, Object> consumerConfigs() {
        String hostname = buildKafkaClientId(logger);

        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, hostname);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        // A group without committed offsets (first deploy of the fixed group id) starts at the
        // beginning of the retained topic rather than skipping the backlog; writes are idempotent.
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        // Bounds the work between two commits so a batch finishes inside the shutdown timeout.
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        logger.info("using Kafka consumer group {} with {} consumer threads", consumerGroup, concurrency);
        return properties;
    }

    public static String buildKafkaClientId(Logger logger) {
        String clientId;
        try {
            clientId = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.error("failed to resolve local hostname, picking random clientId");
            clientId = UUID.randomUUID().toString();
        }
        logger.info("using Kafka client id {}", clientId);
        return clientId;
    }
}
