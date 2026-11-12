package dev.docquery.worker.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes {@code document.uploaded}; errors are handled by the container's error handler. */
@Component
class DocumentUploadedListener {

	private final IngestionMessages messages;

	private final IngestionPipeline pipeline;

	DocumentUploadedListener(IngestionMessages messages, IngestionPipeline pipeline) {
		this.messages = messages;
		this.pipeline = pipeline;
	}

	/** {@code idIsGroup = false}: the consumer group is spring.kafka.consumer.group-id, not the listener id. */
	@KafkaListener(id = "document-uploaded", idIsGroup = false, topics = "${docquery.ingestion.topics.uploaded}")
	void onDocumentUploaded(ConsumerRecord<String, String> record) {
		this.pipeline.process(this.messages.readUploaded(record.value()));
	}

}
