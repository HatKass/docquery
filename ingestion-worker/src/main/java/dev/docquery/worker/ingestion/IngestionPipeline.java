package dev.docquery.worker.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.docquery.worker.chunking.Chunk;
import dev.docquery.worker.chunking.TextChunker;
import dev.docquery.worker.embedding.ChunkEmbedder;
import dev.docquery.worker.embedding.EmbeddedChunks;
import dev.docquery.worker.extraction.ExtractedDocument;
import dev.docquery.worker.extraction.ExtractionException;
import dev.docquery.worker.extraction.TextExtractor;
import dev.docquery.worker.ingestion.IngestionMessages.DocumentUploaded;
import dev.docquery.worker.ingestion.IngestionRepository.ProcessedEvent;
import dev.docquery.worker.storage.DocumentStorage;

/**
 * extract → chunk → embed → index, announcing each step on {@code document.status}.
 * <p>
 * <b>Idempotent:</b> an event that was already processed is not processed again; its recorded
 * outcome is re-announced instead (covers a crash between committing and announcing READY). A
 * document deleted in the meantime is skipped. The final write replaces all chunks of the document
 * in one transaction, so even two concurrent deliveries leave exactly one consistent set of chunks.
 * <p>
 * Exceptions propagate to the Kafka error handler: content problems ({@link ExtractionException})
 * fail the document at once; anything else is retried with backoff (see {@code IngestionKafkaConfiguration}).
 * <p>
 * Each step runs in its own span inside the document's trace, which started at the upload request in
 * api-service and arrived here in the record's {@code traceparent} header (see {@link IngestionMetrics}).
 */
@Service
class IngestionPipeline {

	private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

	private final IngestionRepository repository;

	private final DocumentStorage storage;

	private final TextExtractor extractor;

	private final TextChunker chunker;

	private final ChunkEmbedder embedder;

	private final StatusPublisher status;

	private final IngestionProperties properties;

	private final IngestionMetrics metrics;

	IngestionPipeline(IngestionRepository repository, DocumentStorage storage, TextExtractor extractor,
			TextChunker chunker, ChunkEmbedder embedder, StatusPublisher status, IngestionProperties properties,
			IngestionMetrics metrics) {
		this.metrics = metrics;
		this.repository = repository;
		this.storage = storage;
		this.extractor = extractor;
		this.chunker = chunker;
		this.embedder = embedder;
		this.status = status;
		this.properties = properties;
	}

	void process(DocumentUploaded event) {
		Optional<ProcessedEvent> processed = this.repository.findProcessed(event.eventId());
		if (processed.isPresent()) {
			log.info("Event {} for document {} was already processed ({}); re-announcing the outcome",
					event.eventId(), event.documentId(), processed.get().outcome());
			reannounce(event, processed.get());
			return;
		}
		if (!this.repository.documentExists(event.workspaceId(), event.documentId())) {
			log.info("Skipping event {}: document {} no longer exists", event.eventId(), event.documentId());
			return;
		}
		long started = System.nanoTime();
		this.status.publish(event, StatusPublisher.EXTRACTING, null, null);
		ExtractedDocument document = this.metrics.step("extract", event, () -> extract(event));
		if (!document.hasText()) {
			throw new ExtractionException("No text was found in this document. Scanned documents (images of text) "
					+ "are not supported yet; upload a file with selectable text.");
		}

		this.status.publish(event, StatusPublisher.CHUNKING, document.pageCount(), null);
		List<Chunk> chunks = this.metrics.step("chunk", event, () -> this.chunker.chunk(document.pages()));
		int maxChunks = this.properties.limits().maxChunks();
		if (chunks.size() > maxChunks) {
			throw new ExtractionException("The document is too long to index (" + chunks.size()
					+ " text chunks, the limit is " + maxChunks + "). Split it into smaller files.");
		}

		this.status.publish(event, StatusPublisher.EMBEDDING, document.pageCount(), chunks.size());
		EmbeddedChunks embedded = this.metrics.step("embed", event, () -> {
			try {
				return this.embedder.embed(chunks);
			}
			catch (RuntimeException ex) {
				throw new EmbeddingUnavailableException(ex);
			}
		});

		boolean saved = this.metrics.step("index", event,
				() -> this.repository.saveIndex(event, chunks, embedded, document.pageCount()));
		if (!saved) {
			log.info("Discarding index of document {}: deleted meanwhile or event {} processed concurrently",
					event.documentId(), event.eventId());
			this.repository.findProcessed(event.eventId()).ifPresent(done -> reannounce(event, done));
			return;
		}
		this.status.publish(event, StatusPublisher.READY, document.pageCount(), chunks.size());
		this.metrics.indexed(event, document.pageCount(), chunks.size(), embedded.model(), embedded.inputTokens(),
				embedded.tokensEstimated());
		log.info("Indexed document {} of workspace {}: {} page(s), {} chunk(s), {} {} token(s) with {} in {} ms",
				event.documentId(), event.workspaceId(), document.pageCount(), chunks.size(),
				embedded.tokensEstimated() ? "~" : "", embedded.inputTokens(), embedded.model(),
				(System.nanoTime() - started) / 1_000_000);
	}

	/**
	 * Downloads the file to a temporary file first: a storage failure (transient, retried) is then
	 * cleanly separated from a parsing failure (permanent), and parsers get random access.
	 */
	private ExtractedDocument extract(DocumentUploaded event) {
		Path temp = null;
		try {
			temp = Files.createTempFile("docquery-ingest-", ".bin");
			try (InputStream in = this.storage.open(event.storageKey())) {
				Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			}
			return this.extractor.extract(temp, event.contentType());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not download the stored file of document " + event.documentId(), ex);
		}
		finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				}
				catch (IOException ex) {
					log.warn("Could not delete temporary file {}", temp, ex);
				}
			}
		}
	}

	private void reannounce(DocumentUploaded event, ProcessedEvent processed) {
		if (StatusPublisher.FAILED.equals(processed.outcome())) {
			this.status.publishFailed(event, processed.failureReason());
		}
		else {
			this.status.publish(event, StatusPublisher.READY, processed.pageCount(), processed.chunkCount());
		}
	}

}
