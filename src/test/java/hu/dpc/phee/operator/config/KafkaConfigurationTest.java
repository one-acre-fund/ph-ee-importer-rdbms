package hu.dpc.phee.operator.config;

import hu.dpc.phee.operator.importer.RecordParser;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

@RunWith(MockitoJUnitRunner.class)
public class KafkaConfigurationTest {

    @Mock
    private RecordParser recordParser;

    @InjectMocks
    private KafkaConfiguration configuration;

    @Test
    public void containerCommitsEachRecordAndWaitsForTheCurrentPoll() throws Exception {
        set("kafkaBrokers", "kafka:9092");
        set("consumerGroup", "ph-ee-importer-rdbms");
        set("concurrency", 3);
        set("pollTimeoutMs", 3000L);
        set("retryBackoffMs", 1000L);
        set("autoOffsetReset", "earliest");
        set("maxPollRecords", 100);
        set("shutdownTimeoutMs", 45000L);

        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                (ConcurrentKafkaListenerContainerFactory<?, ?>) configuration.kafkaListenerContainerFactory();

        assertEquals(45000L, factory.getContainerProperties().getShutdownTimeout());
        // offsets are committed record by record, so a shutdown cut short redoes only in-flight records
        assertEquals(ContainerProperties.AckMode.RECORD, factory.getContainerProperties().getAckMode());
        assertEquals(100, configuration.consumerConfigs().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));

        // the context-close path is bounded by the lifecycle processor, not by the container property
        DefaultLifecycleProcessor processor = configuration.lifecycleProcessor();
        Field timeout = DefaultLifecycleProcessor.class.getDeclaredField("timeoutPerShutdownPhase");
        timeout.setAccessible(true);
        assertEquals(45000L, timeout.get(processor));
    }

    private void set(String name, Object value) throws Exception {
        Field field = KafkaConfiguration.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(configuration, value);
    }
}
