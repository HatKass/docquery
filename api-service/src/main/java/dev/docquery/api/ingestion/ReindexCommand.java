package dev.docquery.api.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.docquery.api.document.DocumentService;

/**
 * One-off maintenance command: re-index documents after the embedding model changed.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--docquery.reindex.run=true --docquery.reindex.embedding-model=nomic-embed-text"
 * </pre>
 *
 * Queues every finished document whose chunks were embedded with another model than
 * {@code docquery.reindex.embedding-model} (or every finished document with
 * {@code --docquery.reindex.all=true}), then exits. The queued events are relayed by the outbox
 * (by this process if it gets the chance, otherwise by the running api-service), and the worker
 * re-indexes them with the model it is configured with.
 */
@Component
@ConditionalOnBooleanProperty("docquery.reindex.run")
class ReindexCommand implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ReindexCommand.class);

	private final DocumentService documents;

	private final OutboxRelay relay;

	private final ConfigurableApplicationContext context;

	private final String embeddingModel;

	private final boolean all;

	ReindexCommand(DocumentService documents, OutboxRelay relay, ConfigurableApplicationContext context,
			@Value("${docquery.reindex.embedding-model:}") String embeddingModel,
			@Value("${docquery.reindex.all:false}") boolean all) {
		this.documents = documents;
		this.relay = relay;
		this.context = context;
		this.embeddingModel = embeddingModel;
		this.all = all;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!this.all && !StringUtils.hasText(this.embeddingModel)) {
			throw new IllegalArgumentException(
					"Set --docquery.reindex.embedding-model=<current model> (or --docquery.reindex.all=true)");
		}
		int queued = this.documents.reindexForEmbeddingModel(this.embeddingModel, this.all);
		this.relay.relayPending();
		log.info("Re-index queued {} document(s) ({}).", queued,
				this.all ? "all finished documents" : "chunks not embedded with '" + this.embeddingModel + "'");
		System.exit(SpringApplication.exit(this.context, () -> 0));
	}

}
