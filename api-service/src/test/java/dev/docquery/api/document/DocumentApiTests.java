package dev.docquery.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import dev.docquery.api.storage.ObjectStorage;
import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.TestUser;

/** The document API end to end: real Postgres and a real S3 API (SeaweedFS) via Testcontainers. */
class DocumentApiTests extends ApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectStorage storage;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DocumentStatusService statusService;

	@Autowired
	DocumentEventStreams streams;

	TestUser owner;

	TestUser member;

	TestUser viewer;

	UUID workspaceId;

	@BeforeEach
	void workspaceWithAllRoles() {
		this.owner = TestUser.random("doc-owner");
		this.member = TestUser.random("doc-member");
		this.viewer = TestUser.random("doc-viewer");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Docs");
		this.api.addMember(this.owner, this.workspaceId, this.member, "MEMBER");
		this.api.addMember(this.owner, this.workspaceId, this.viewer, "VIEWER");
	}

	@Test
	void memberUploadsADocumentThatStartsAsUploaded() {
		byte[] pdf = SampleFiles.pdf();
		MvcTestResult result = this.api.upload(this.member, this.workspaceId, "C:\\fakepath\\Handbook.pdf", pdf);

		assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.filename").isEqualTo("Handbook.pdf");
			json.assertThat().extractingPath("$.contentType").isEqualTo("application/pdf");
			json.assertThat().extractingPath("$.sizeBytes").isEqualTo(pdf.length);
			json.assertThat().extractingPath("$.status").isEqualTo("UPLOADED");
			json.assertThat().extractingPath("$.workspaceId").isEqualTo(this.workspaceId.toString());
			json.assertThat().extractingPath("$.uploadedByName").isEqualTo("Test " + this.member.username());
			json.assertThat().extractingPath("$.pageCount").isNull();
			json.assertThat().extractingPath("$.failureReason").isNull();
		});
		UUID id = UUID.fromString(this.api.body(result).get("id").asString());
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
			.isEqualTo("/api/workspaces/" + this.workspaceId + "/documents/" + id);

		String key = storageKey(id);
		assertThat(key).startsWith("workspaces/" + this.workspaceId + "/documents/").doesNotContain("Handbook");
		assertThat(this.storage.exists(key)).isTrue();
	}

	@Test
	void everyMemberCanListAndReadNewestFirst() {
		UUID first = this.api.uploadDocument(this.member, this.workspaceId, "a.txt", SampleFiles.text("first"));
		UUID second = this.api.uploadDocument(this.owner, this.workspaceId, "b.md", SampleFiles.text("# second"));

		assertThat(this.api.get(this.viewer, documents())).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].id")
			.asArray()
			.containsExactly(second.toString(), first.toString());
		assertThat(this.api.get(this.viewer, documents() + "/" + first)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.contentType")
			.isEqualTo("text/plain");
	}

	@Test
	void downloadReturnsTheOriginalBytesWithSafeHeaders() {
		byte[] text = SampleFiles.text("Ünïcode text");
		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "notes é.txt", text);

		MvcTestResult result = this.api.get(this.viewer, documents() + "/" + id + "/content");

		assertThat(result).hasStatusOk();
		assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(text);
		assertThat(result.getResponse().getContentType()).isEqualTo("text/plain;charset=UTF-8");
		assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;")
			.contains("filename*=UTF-8''notes%20%C3%A9.txt");
		assertThat(result.getResponse().getHeader("Content-Security-Policy")).contains("sandbox");
		assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
	}

	@Test
	void wordDocumentsDownloadAsAttachment() {
		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "contract.docx", SampleFiles.docx());

		MvcTestResult result = this.api.get(this.member, documents() + "/" + id + "/content");

		assertThat(result).hasStatusOk();
		assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
	}

	@Test
	void viewersCannotUploadOrDelete() {
		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "a.txt", SampleFiles.text("x"));

		assertThat(this.api.upload(this.viewer, this.workspaceId, "b.txt", SampleFiles.text("y")))
			.hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:insufficient-role");
		assertThat(this.api.delete(this.viewer, documents() + "/" + id)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(this.api.get(this.viewer, documents())).bodyJson().extractingPath("$.length()").isEqualTo(1);
	}

	@Test
	void deleteRemovesTheRowAndTheStoredFile() {
		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "a.pdf", SampleFiles.pdf());
		String key = storageKey(id);

		assertThat(this.api.delete(this.member, documents() + "/" + id)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(this.api.get(this.member, documents() + "/" + id)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:document-not-found");
		assertThat(this.storage.exists(key)).isFalse();
		assertThat(this.api.delete(this.member, documents() + "/" + id)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsFilesWhoseContentIsNotWhatTheExtensionClaims() {
		MvcTestResult result = this.api.upload(this.member, this.workspaceId, "invoice.pdf",
				"MZ fake executable".getBytes(StandardCharsets.US_ASCII));

		assertThat(result).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:file-content-mismatch");
		assertThat(this.api.get(this.member, documents())).bodyJson().extractingPath("$.length()").isEqualTo(0);
	}

	@Test
	void rejectsUnsupportedAndEmptyFiles() {
		assertThat(this.api.upload(this.member, this.workspaceId, "image.png", new byte[] { 1, 2, 3 }))
			.hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(this.api.upload(this.member, this.workspaceId, "empty.txt", new byte[0]))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:empty-file");
	}

	@Test
	void rejectsFilesOverTheConfiguredLimit() {
		byte[] tooBig = new byte[1024 * 1024 + 1];
		java.util.Arrays.fill(tooBig, (byte) 'a');

		assertThat(this.api.upload(this.member, this.workspaceId, "big.txt", tooBig)).hasStatus(HttpStatus.CONTENT_TOO_LARGE)
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.type").isEqualTo("urn:docquery:problem:file-too-large");
				json.assertThat().extractingPath("$.detail").asString().contains("1 MB");
			});
	}

	@Test
	void rejectsTheSameContentTwiceInOneWorkspace() {
		byte[] pdf = SampleFiles.pdf();
		UUID first = this.api.uploadDocument(this.member, this.workspaceId, "a.pdf", pdf);

		assertThat(this.api.upload(this.owner, this.workspaceId, "copy-of-a.pdf", pdf)).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.type").isEqualTo("urn:docquery:problem:duplicate-document");
				json.assertThat().extractingPath("$.existingDocumentId").isEqualTo(first.toString());
			});

		UUID otherWorkspace = this.api.createWorkspace(this.member, "Other");
		this.api.uploadDocument(this.member, otherWorkspace, "a.pdf", pdf);
	}

	@Test
	void uploadPolicyDescribesTheLimits() {
		assertThat(this.api.get(this.viewer, "/api/documents/upload-policy")).hasStatusOk().bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.maxFileSizeBytes").isEqualTo(1024 * 1024);
			json.assertThat().extractingPath("$.allowedExtensions").asArray().contains("pdf", "docx", "txt", "md");
			json.assertThat().extractingPath("$.requeueAfterSeconds").isEqualTo(600);
		});
	}

	@Test
	void deletingTheWorkspaceRemovesItsStoredFiles() {
		UUID a = this.api.uploadDocument(this.member, this.workspaceId, "a.pdf", SampleFiles.pdf());
		UUID b = this.api.uploadDocument(this.member, this.workspaceId, "b.txt", SampleFiles.text("b"));
		String keyA = storageKey(a);
		String keyB = storageKey(b);

		assertThat(this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(this.storage.exists(keyA)).isFalse();
		assertThat(this.storage.exists(keyB)).isFalse();
		assertThat(this.jdbc.sql("SELECT count(*) FROM document WHERE id IN (:a, :b)")
			.param("a", a)
			.param("b", b)
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void statusModelAppliesForwardStepsAndIgnoresReplays() {
		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "a.pdf", SampleFiles.pdf());

		assertThat(this.statusService.transition(id, DocumentStatus.EXTRACTING, null, null, null)).isTrue();
		assertThat(this.statusService.transition(id, DocumentStatus.CHUNKING, null, 12, null)).isTrue();
		assertThat(this.statusService.transition(id, DocumentStatus.EXTRACTING, null, null, null)).as("replayed event")
			.isFalse();
		assertThat(this.statusService.transition(id, DocumentStatus.EMBEDDING, null, null, 40)).isTrue();
		assertThat(this.statusService.transition(id, DocumentStatus.READY, null, null, null)).isTrue();
		assertThat(this.statusService.transition(UUID.randomUUID(), DocumentStatus.EXTRACTING, null, null, null))
			.as("deleted document")
			.isFalse();

		assertThat(this.api.get(this.viewer, documents() + "/" + id)).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.status").isEqualTo("READY");
			json.assertThat().extractingPath("$.pageCount").isEqualTo(12);
			json.assertThat().extractingPath("$.chunkCount").isEqualTo(40);
		});
	}

	@Test
	void failedDocumentsCarryAReadableReasonUntilRetried() {
		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "a.pdf", SampleFiles.pdf());

		this.statusService.transition(id, DocumentStatus.EXTRACTING, null, null, null);
		this.statusService.transition(id, DocumentStatus.FAILED, "  The PDF is password-protected.  ", null, null);
		assertThat(this.api.get(this.member, documents() + "/" + id)).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.status").isEqualTo("FAILED");
			json.assertThat().extractingPath("$.failureReason").isEqualTo("The PDF is password-protected.");
		});

		this.statusService.transition(id, DocumentStatus.UPLOADED, null, null, null);
		assertThat(this.api.get(this.member, documents() + "/" + id)).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.status").isEqualTo("UPLOADED");
			json.assertThat().extractingPath("$.failureReason").isNull();
		});
	}

	@Test
	void liveStreamDeliversCreatedUpdatedAndDeletedEvents() throws Exception {
		MvcResult stream = openStream(this.viewer);

		UUID id = this.api.uploadDocument(this.member, this.workspaceId, "live.pdf", SampleFiles.pdf());
		this.statusService.transition(id, DocumentStatus.EXTRACTING, null, null, null);
		this.api.delete(this.member, documents() + "/" + id);

		String events = stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(events).contains("event:ready")
			.contains("event:document.created")
			.contains("\"filename\":\"live.pdf\"")
			.contains("event:document.updated")
			.contains("\"status\":\"EXTRACTING\"")
			.contains("event:document.deleted")
			.contains("\"documentId\":\"" + id + "\"");
		assertThat(events.indexOf("document.created")).isLessThan(events.indexOf("document.updated"));
		assertThat(events.indexOf("document.updated")).isLessThan(events.indexOf("document.deleted"));
	}

	@Test
	void liveStreamOnlyCarriesItsOwnWorkspace() throws Exception {
		MvcResult stream = openStream(this.viewer);

		UUID otherWorkspace = this.api.createWorkspace(this.member, "Elsewhere");
		this.api.uploadDocument(this.member, otherWorkspace, "secret.pdf", SampleFiles.pdf());

		assertThat(stream.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("event:ready")
			.doesNotContain("secret.pdf")
			.doesNotContain("document.created");
	}

	@Test
	void removingAMemberClosesTheirLiveStreamImmediately() throws Exception {
		openStream(this.viewer);
		assertThat(this.streams.subscriberCount(this.workspaceId)).isEqualTo(1);

		UUID viewerId = this.api.signIn(this.viewer);
		this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId + "/members/" + viewerId);

		assertThat(this.streams.subscriberCount(this.workspaceId)).isZero();
	}

	private MvcResult openStream(TestUser user) throws Exception {
		return this.mockMvc
			.perform(get(documents() + "/events").with(user.auth()).accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(request().asyncStarted())
			.andReturn();
	}

	private String documents() {
		return "/api/workspaces/" + this.workspaceId + "/documents";
	}

	private String storageKey(UUID documentId) {
		return this.jdbc.sql("SELECT storage_key FROM document WHERE id = :id")
			.param("id", documentId)
			.query(String.class)
			.single();
	}

}
