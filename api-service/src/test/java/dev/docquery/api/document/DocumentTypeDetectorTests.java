package dev.docquery.api.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.support.SampleFiles;

class DocumentTypeDetectorTests {

	@TempDir
	Path dir;

	final DocumentTypeDetector detector = new DocumentTypeDetector();

	@Test
	void acceptsEachSupportedType() throws IOException {
		assertThat(detect("report.pdf", SampleFiles.pdf())).isEqualTo(DocumentType.PDF);
		assertThat(detect("Report.PDF", SampleFiles.pdf())).isEqualTo(DocumentType.PDF);
		assertThat(detect("contract.docx", SampleFiles.docx())).isEqualTo(DocumentType.DOCX);
		assertThat(detect("notes.txt", SampleFiles.text("hello"))).isEqualTo(DocumentType.TEXT);
		assertThat(detect("README.md", SampleFiles.text("# Title"))).isEqualTo(DocumentType.MARKDOWN);
		assertThat(detect("guide.markdown", SampleFiles.text("# Guide"))).isEqualTo(DocumentType.MARKDOWN);
	}

	@Test
	void acceptsPdfSignatureAfterLeadingJunkWithinFirstKilobyte() throws IOException {
		byte[] pdf = ("\n\n   " + new String(SampleFiles.pdf(), StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII);
		assertThat(detect("scan.pdf", pdf)).isEqualTo(DocumentType.PDF);
	}

	@Test
	void acceptsUtf8TextWithBomAndNonAsciiCharacters() throws IOException {
		byte[] text = "﻿Zürich – café 東京\tok\r\n".getBytes(StandardCharsets.UTF_8);
		assertThat(detect("intl.txt", text)).isEqualTo(DocumentType.TEXT);
	}

	@Test
	void rejectsUnsupportedExtensions() {
		assertRejected("photo.png", new byte[] { (byte) 0x89, 'P', 'N', 'G' }, "unsupported-file-type");
		assertRejected("legacy.doc", SampleFiles.text("x"), "unsupported-file-type");
		assertRejected("no-extension", SampleFiles.pdf(), "unsupported-file-type");
	}

	@Test
	void rejectsExecutableDisguisedAsPdf() {
		assertRejected("invoice.pdf", "MZ\u0090\u0000 this program cannot be run in DOS mode".getBytes(StandardCharsets.ISO_8859_1),
				"file-content-mismatch");
	}

	@Test
	void rejectsContentThatDoesNotMatchTheExtension() {
		assertRejected("notes.txt", SampleFiles.pdf(), "file-content-mismatch");
		assertRejected("contract.docx", SampleFiles.pdf(), "file-content-mismatch");
		assertRejected("report.pdf", SampleFiles.docx(), "file-content-mismatch");
	}

	@Test
	void rejectsZipThatIsNotAWordDocument() {
		assertRejected("sheet.docx", SampleFiles.xlsxLikeZip(), "file-content-mismatch");
	}

	@Test
	void rejectsTruncatedZip() {
		byte[] docx = SampleFiles.docx();
		byte[] truncated = java.util.Arrays.copyOf(docx, docx.length / 2);
		assertRejected("broken.docx", truncated, "file-content-mismatch");
	}

	@Test
	void rejectsBinaryDataInTextFiles() {
		assertRejected("data.txt", new byte[] { 'a', 'b', 0, 'c' }, "file-content-mismatch");
	}

	@Test
	void rejectsTextThatIsNotUtf8() {
		byte[] latin1 = "Größe".getBytes(StandardCharsets.ISO_8859_1);
		assertRejected("legacy.txt", latin1, "unsupported-text-encoding");
	}

	private DocumentType detect(String filename, byte[] content) throws IOException {
		Path file = Files.write(this.dir.resolve("upload-" + System.nanoTime()), content);
		return this.detector.detect(filename, file);
	}

	private void assertRejected(String filename, byte[] content, String code) {
		assertThatThrownBy(() -> detect(filename, content)).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
			assertThat(ex.getBody().getType()).hasToString(ApiException.TYPE_PREFIX + code);
			assertThat(ex.getBody().getDetail()).contains(filename);
		});
	}

}
