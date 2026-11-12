package dev.docquery.api.document;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.docquery.api.document.DocumentDtos.DocumentResponse;
import dev.docquery.api.document.DocumentDtos.UploadPolicyResponse;
import dev.docquery.api.document.DocumentService.DocumentContent;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceAccess;
import dev.docquery.api.workspace.WorkspaceRole;

@RestController
class DocumentController {

	private static final String BASE = "/api/workspaces/{workspaceId}/documents";

	private final DocumentService documents;

	private final DocumentEventStreams streams;

	private final WorkspaceAccess access;

	DocumentController(DocumentService documents, DocumentEventStreams streams, WorkspaceAccess access) {
		this.documents = documents;
		this.streams = streams;
		this.access = access;
	}

	@GetMapping("/api/documents/upload-policy")
	UploadPolicyResponse uploadPolicy() {
		return this.documents.uploadPolicy();
	}

	@GetMapping(BASE)
	List<DocumentResponse> list(@PathVariable UUID workspaceId, CurrentUser user) {
		return this.documents.list(workspaceId, user);
	}

	@PostMapping(path = BASE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<DocumentResponse> upload(@PathVariable UUID workspaceId, @RequestParam("file") MultipartFile file,
			CurrentUser user) {
		DocumentResponse created = this.documents.upload(workspaceId, file, user);
		return ResponseEntity.created(URI.create("/api/workspaces/" + workspaceId + "/documents/" + created.id()))
			.body(created);
	}

	/** Live document changes of one workspace (Server-Sent Events). */
	@GetMapping(path = BASE + "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter events(@PathVariable UUID workspaceId, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.VIEWER);
		return this.streams.open(workspaceId, user.id());
	}

	@GetMapping(BASE + "/{documentId}")
	DocumentResponse get(@PathVariable UUID workspaceId, @PathVariable UUID documentId, CurrentUser user) {
		return this.documents.get(workspaceId, documentId, user);
	}

	/**
	 * The original file. PDFs and text open in the browser; Word files download. User-supplied
	 * content is served with {@code nosniff} (Spring Security default) and a sandboxing CSP, so even
	 * a crafted file can never run script on the API's origin.
	 */
	@GetMapping(BASE + "/{documentId}/content")
	ResponseEntity<InputStreamResource> content(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
			CurrentUser user) {
		DocumentContent content = this.documents.content(workspaceId, documentId, user);
		DocumentResponse document = content.document();
		boolean text = document.contentType().startsWith("text/");
		MediaType mediaType = text ? new MediaType(MediaType.parseMediaType(document.contentType()), StandardCharsets.UTF_8)
				: MediaType.parseMediaType(document.contentType());
		boolean inline = text || MediaType.APPLICATION_PDF.equalsTypeAndSubtype(mediaType);
		ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
			.filename(document.filename(), StandardCharsets.UTF_8)
			.build();
		return ResponseEntity.ok()
			.contentType(mediaType)
			.contentLength(document.sizeBytes())
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.header("Content-Security-Policy", "sandbox; default-src 'none'")
			.cacheControl(CacheControl.noStore().cachePrivate())
			.body(new InputStreamResource(content.stream()));
	}

	/** Queues the document for indexing again: ready, failed, or queued for too long (see DocumentService#reindex). */
	@PostMapping(BASE + "/{documentId}/reindex")
	ResponseEntity<DocumentResponse> reindex(@PathVariable UUID workspaceId, @PathVariable UUID documentId,
			CurrentUser user) {
		return ResponseEntity.accepted().body(this.documents.reindex(workspaceId, documentId, user));
	}

	@DeleteMapping(BASE + "/{documentId}")
	ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID documentId, CurrentUser user) {
		this.documents.delete(workspaceId, documentId, user);
		return ResponseEntity.noContent().build();
	}

}
