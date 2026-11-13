package dev.docquery.api.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.document.DocumentDtos.DocumentResponse;
import dev.docquery.api.document.DocumentDtos.UploadPolicyResponse;
import dev.docquery.api.document.DocumentRepository.IngestionSource;
import dev.docquery.api.document.DocumentRepository.NewDocument;
import dev.docquery.api.storage.ObjectStorage;
import dev.docquery.api.usage.QuotaService;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceAccess;
import dev.docquery.api.workspace.WorkspaceEvents;
import dev.docquery.api.workspace.WorkspaceRole;

/**
 * Document use cases: upload, list, download, re-index, delete. Each starts with a {@link WorkspaceAccess}
 * check, and every query is scoped by the same workspace id.
 * <p>
 * Upload order: validate → store the file → insert the row. If the insert fails, the stored file is
 * removed again; a row therefore never points at a missing file. On delete the row goes first, then
 * the file, so a storage outage can at worst leave an unreferenced file, never a broken document.
 */
@Service
public class DocumentService {

	private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

	private final DocumentRepository documents;

	private final WorkspaceAccess access;

	private final ObjectStorage storage;

	private final DocumentTypeDetector detector;

	private final DocumentProperties properties;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final DocumentStatusService statusService;

	private final QuotaService quotas;

	private final MeterRegistry meters;

	DocumentService(DocumentRepository documents, WorkspaceAccess access, ObjectStorage storage,
			DocumentTypeDetector detector, DocumentProperties properties, ApplicationEventPublisher events,
			TransactionTemplate transactions, DocumentStatusService statusService, QuotaService quotas,
			MeterRegistry meters) {
		this.quotas = quotas;
		this.meters = meters;
		this.documents = documents;
		this.access = access;
		this.storage = storage;
		this.detector = detector;
		this.properties = properties;
		this.events = events;
		this.transactions = transactions;
		this.statusService = statusService;
	}

	/** Storage layout: one prefix per workspace (so it can be removed in one go), random object names. */
	static String workspacePrefix(UUID workspaceId) {
		return "workspaces/" + workspaceId + "/documents/";
	}

	public UploadPolicyResponse uploadPolicy() {
		return new UploadPolicyResponse(this.properties.maxFileSize().toBytes(), DocumentType.allExtensions(),
				Arrays.stream(DocumentType.values()).map(DocumentType::mediaType).toList(),
				this.properties.requeueAfter().toSeconds());
	}

	public List<DocumentResponse> list(UUID workspaceId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		return this.documents.findAll(workspaceId);
	}

	public DocumentResponse get(UUID workspaceId, UUID documentId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		return this.documents.find(workspaceId, documentId).orElseThrow(() -> notFound(documentId));
	}

	public DocumentResponse upload(UUID workspaceId, MultipartFile file, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.MEMBER);
		String filename = FileNames.sanitize(file.getOriginalFilename());
		if (filename == null) {
			throw ApiException.unprocessable("missing-filename", "The uploaded file has no usable name.");
		}
		if (file.isEmpty()) {
			throw ApiException.unprocessable("empty-file", "'" + filename + "' is empty.");
		}
		if (file.getSize() > this.properties.maxFileSize().toBytes()) {
			throw fileTooLarge(this.properties.maxFileSize().toMegabytes());
		}
		// Early, cheap check; the binding count happens in the insert transaction below.
		this.quotas.checkUploadAllowed(user.id());
		Path temp = null;
		try {
			temp = Files.createTempFile("docquery-upload-", ".tmp");
			try (InputStream in = file.getInputStream()) {
				Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			}
			long size = Files.size(temp);
			DocumentType type = this.detector.detect(filename, temp);
			String sha256 = sha256(temp);
			rejectDuplicate(workspaceId, sha256, filename);

			UUID id = UUID.randomUUID();
			String key = workspacePrefix(workspaceId) + UUID.randomUUID();
			this.storage.put(key, temp, type.mediaType());
			try {
				DocumentResponse created = this.transactions.execute(status -> {
					// Limits are checked and counted with the insert: a failed upload uses up nothing.
					this.quotas.requireDocumentSlot(workspaceId);
					this.quotas.countUpload(user.id());
					this.documents.insert(new NewDocument(id, workspaceId, filename, type.mediaType(), size,
							sha256, key, user.id()));
					DocumentResponse inserted = this.documents.find(workspaceId, id).orElseThrow();
					this.events.publishEvent(DocumentChangedEvent.created(inserted));
					this.events.publishEvent(new DocumentIngestionRequested(id, workspaceId, key, type.mediaType(),
							filename, user.id(), DocumentIngestionRequested.Reason.UPLOAD));
					return inserted;
				});
				Counter.builder("docquery.documents.uploaded")
					.description("Documents accepted for ingestion, by detected type")
					.tag("type", type.name().toLowerCase(Locale.ROOT))
					.register(this.meters)
					.increment();
				return created;
			}
			catch (RuntimeException ex) {
				deleteQuietly(key);
				if (ex instanceof DuplicateKeyException) {
					// Lost a race against an identical concurrent upload.
					rejectDuplicate(workspaceId, sha256, filename);
				}
				throw ex;
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not process upload", ex);
		}
		finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				}
				catch (IOException ex) {
					log.warn("Could not delete temporary upload file {}", temp, ex);
				}
			}
		}
	}

	/**
	 * Queues a document for indexing again:
	 * <ul>
	 * <li>READY or FAILED: retry after a failure, or pick up a new embedding model. The document goes
	 * back to UPLOADED (counts cleared); its old chunks stay searchable until the worker replaces them.</li>
	 * <li>UPLOADED for longer than {@code docquery.documents.requeue-after}: the safety net for a
	 * request that was lost or never created. The status stays UPLOADED, its timer restarts.</li>
	 * </ul>
	 * Anything else is in progress and answered with 409. The new request is queued in the same
	 * transaction; a duplicate of a request still in flight is harmless (the worker is idempotent).
	 */
	public DocumentResponse reindex(UUID workspaceId, UUID documentId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.MEMBER);
		return this.transactions.execute(status -> {
			IngestionSource source = this.documents.findIngestionSource(workspaceId, documentId)
				.orElseThrow(() -> notFound(documentId));
			return reindex(source);
		});
	}

	/**
	 * Platform admin: retry a document of any workspace (failure list, dead-letter retry). Same rules as
	 * {@link #reindex(UUID, UUID, CurrentUser)}. Callers must have checked the admin role.
	 */
	public DocumentResponse reindexAsAdmin(UUID documentId) {
		return this.transactions.execute(status -> {
			IngestionSource source = this.documents.findIngestionSourceById(documentId)
				.orElseThrow(() -> notFound(documentId));
			return reindex(source);
		});
	}

	/** Within a transaction. */
	private DocumentResponse reindex(IngestionSource source) {
		if (!requestReindex(source) && !requeueIfStuck(source)) {
			throw ApiException.conflict("document-busy", "The document is still being processed. You can retry "
					+ "it once it is ready, has failed, or has been queued for more than " + requeueAfterText() + ".");
		}
		return this.documents.find(source.workspaceId(), source.id()).orElseThrow();
	}

	/** Within a transaction: re-queue a document stuck in UPLOADED; false if it is not stuck. */
	private boolean requeueIfStuck(IngestionSource source) {
		if (!this.documents.touchIfQueuedLongerThan(source.workspaceId(), source.id(),
				this.properties.requeueAfter().toSeconds())) {
			return false;
		}
		this.documents.find(source.workspaceId(), source.id())
			.ifPresent(updated -> this.events.publishEvent(DocumentChangedEvent.updated(updated)));
		this.events.publishEvent(new DocumentIngestionRequested(source.id(), source.workspaceId(), source.storageKey(),
				source.contentType(), source.filename(), source.uploadedBy(), DocumentIngestionRequested.Reason.REQUEUE));
		log.info("Re-queued document {} of workspace {}: still queued after {}", source.id(), source.workspaceId(),
				this.properties.requeueAfter());
		return true;
	}

	private String requeueAfterText() {
		long minutes = this.properties.requeueAfter().toMinutes();
		return (minutes >= 1) ? minutes + (minutes == 1 ? " minute" : " minutes")
				: this.properties.requeueAfter().toSeconds() + " seconds";
	}

	/**
	 * Maintenance: re-indexes finished documents of all workspaces whose chunks were embedded with a
	 * model other than {@code currentModel} (or all of them). One transaction per document.
	 * @return the number of documents queued
	 */
	public int reindexForEmbeddingModel(String currentModel, boolean all) {
		int queued = 0;
		for (IngestionSource source : this.documents.findReindexCandidates(currentModel, all)) {
			if (Boolean.TRUE.equals(this.transactions.execute(status -> requestReindex(source)))) {
				queued++;
			}
		}
		return queued;
	}

	/** Within a transaction: back to UPLOADED and queue a request; false if the document is busy. */
	private boolean requestReindex(IngestionSource source) {
		if (!this.statusService.transition(source.id(), DocumentStatus.UPLOADED, null, null, null)) {
			return false;
		}
		this.events.publishEvent(new DocumentIngestionRequested(source.id(), source.workspaceId(), source.storageKey(),
				source.contentType(), source.filename(), source.uploadedBy(), DocumentIngestionRequested.Reason.REINDEX));
		return true;
	}

	/** Content of a document for download; the caller must close the stream. */
	public DocumentContent content(UUID workspaceId, UUID documentId, CurrentUser user) {
		DocumentResponse document = get(workspaceId, documentId, user);
		String key = this.documents.findStorageKey(workspaceId, documentId).orElseThrow(() -> notFound(documentId));
		return new DocumentContent(document, this.storage.get(key));
	}

	public void delete(UUID workspaceId, UUID documentId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.MEMBER);
		String key = this.transactions.execute(status -> {
			String storageKey = this.documents.delete(workspaceId, documentId).orElseThrow(() -> notFound(documentId));
			this.events.publishEvent(DocumentChangedEvent.deleted(workspaceId, documentId));
			return storageKey;
		});
		// Chunks and vectors went with the row (ON DELETE CASCADE); a worker still processing the
		// document notices it is gone and discards its result.
		deleteQuietly(key);
	}

	/** Removes all stored files of a deleted workspace (its rows are already gone by cascade). */
	@TransactionalEventListener(fallbackExecution = true)
	void onWorkspaceDeleted(WorkspaceEvents.WorkspaceDeleted event) {
		String prefix = workspacePrefix(event.workspaceId());
		try {
			int deleted = this.storage.deleteAll(prefix);
			log.info("Deleted {} stored file(s) of workspace {}", deleted, event.workspaceId());
		}
		catch (RuntimeException ex) {
			log.error("Could not delete stored files under '{}'; remove them manually", prefix, ex);
		}
	}

	public record DocumentContent(DocumentResponse document, InputStream stream) {
	}

	private void rejectDuplicate(UUID workspaceId, String sha256, String filename) {
		this.documents.findIdBySha256(workspaceId, sha256).ifPresent(existing -> {
			ApiException conflict = ApiException.conflict("duplicate-document",
					"'" + filename + "' has already been uploaded to this workspace.");
			conflict.getBody().setProperty("existingDocumentId", existing);
			throw conflict;
		});
	}

	private void deleteQuietly(String key) {
		try {
			this.storage.delete(key);
		}
		catch (RuntimeException ex) {
			log.error("Could not delete stored file '{}'; it is no longer referenced and can be removed", key, ex);
		}
	}

	static ApiException fileTooLarge(long maxMegabytes) {
		return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file-too-large", "File too large",
				"Files can be at most " + maxMegabytes + " MB.");
	}

	private static ApiException notFound(UUID documentId) {
		return ApiException.notFound("document-not-found", "Document " + documentId + " was not found.");
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
			in.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

}
