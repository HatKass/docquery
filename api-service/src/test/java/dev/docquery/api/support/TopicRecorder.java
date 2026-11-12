package dev.docquery.api.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;

/**
 * Reads topics from the beginning (no consumer group), so a test can wait for the records it
 * expects. Tests filter by their own document ids, which keeps them independent of each other.
 */
public final class TopicRecorder implements AutoCloseable {

	private final KafkaConsumer<String, String> consumer;

	private final List<ConsumerRecord<String, String>> records = new ArrayList<>();

	public TopicRecorder(String bootstrapServers, String... topics) {
		this.consumer = new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.CLIENT_ID_CONFIG, "test-recorder-" + UUID.randomUUID(),
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new StringDeserializer(), new StringDeserializer());
		List<TopicPartition> partitions = new ArrayList<>();
		for (String topic : topics) {
			this.consumer.partitionsFor(topic, Duration.ofSeconds(30))
				.forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
		}
		this.consumer.assign(partitions);
		this.consumer.seekToBeginning(partitions);
	}

	/** Polls until {@code condition} holds for the records with this key on this topic. */
	public List<ConsumerRecord<String, String>> await(String topic, String key,
			Predicate<List<ConsumerRecord<String, String>>> condition) {
		Awaitility.await()
			.atMost(Duration.ofSeconds(60))
			.pollInterval(Duration.ofMillis(100))
			.until(() -> condition.test(poll(topic, key)));
		return poll(topic, key);
	}

	/** Everything received so far with this key on this topic, in order. */
	public List<ConsumerRecord<String, String>> poll(String topic, String key) {
		this.consumer.poll(Duration.ofMillis(200)).forEach(this.records::add);
		return this.records.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).toList();
	}

	@Override
	public void close() {
		this.consumer.close();
	}

}
