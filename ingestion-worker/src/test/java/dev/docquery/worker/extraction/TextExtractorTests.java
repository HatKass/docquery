package dev.docquery.worker.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.docquery.worker.support.TestDocuments;

class TextExtractorTests {

	private final TextExtractor extractor = new TextExtractor(10_000);

	@TempDir
	Path tempDir;

	@Test
	void pdfKeepsPageNumbers() {
		byte[] pdf = TestDocuments.pdf(List.of(List.of("Welcome to Acme.", "Office hours are 9 to 5."),
				List.of("Holidays: 25 days per year."), List.of("Expenses need a receipt.")));

		ExtractedDocument document = extract(pdf, TextExtractor.PDF);

		assertThat(document.pageCount()).isEqualTo(3);
		assertThat(document.pages()).extracting(PageText::pageNumber).containsExactly(1, 2, 3);
		assertThat(document.pages().get(0).text()).contains("Welcome to Acme.").contains("Office hours are 9 to 5.");
		assertThat(document.pages().get(1).text()).isEqualTo("Holidays: 25 days per year.");
		assertThat(document.pages().get(2).text()).isEqualTo("Expenses need a receipt.");
	}

	@Test
	void pdfWithoutTextHasPagesButNoText() {
		ExtractedDocument document = extract(TestDocuments.pdf(List.of(List.of(), List.of())), TextExtractor.PDF);

		assertThat(document.pageCount()).isEqualTo(2);
		assertThat(document.hasText()).isFalse();
	}

	@Test
	void docxIsOneUnpaginatedBlockWithParagraphs() {
		byte[] docx = TestDocuments.docx(List.of("Travel policy", "Book economy class.", "Submit receipts."));

		ExtractedDocument document = extract(docx, TextExtractor.DOCX);

		assertThat(document.pageCount()).isNull();
		assertThat(document.pages()).singleElement().satisfies(page -> {
			assertThat(page.pageNumber()).isNull();
			// Paragraphs are separated by a blank line, which the chunker prefers as a split point.
			assertThat(page.text()).isEqualTo("Travel policy\n\nBook economy class.\n\nSubmit receipts.");
		});
	}

	@Test
	void plainTextAndMarkdownAreNormalized() {
		String text = "﻿# Title\r\n\r\n\r\n\r\nSome   text\twith  spaces.  \r\nNext line.\u0007";

		ExtractedDocument document = extract(text.getBytes(StandardCharsets.UTF_8), TextExtractor.MARKDOWN);

		assertThat(document.pageCount()).isNull();
		assertThat(document.pages()).singleElement()
			.extracting(PageText::text)
			.isEqualTo("# Title\n\nSome text with spaces.\nNext line.");
	}

	@Test
	void passwordProtectedPdfIsRejectedWithAReadableReason() {
		byte[] pdf = TestDocuments.encryptedPdf("Top secret");

		assertThatExceptionOfType(ExtractionException.class).isThrownBy(() -> extract(pdf, TextExtractor.PDF))
			.withMessageContaining("password-protected");
	}

	@Test
	void damagedPdfIsRejectedWithAReadableReason() {
		byte[] pdf = "%PDF-1.7\nthis is not really a pdf".getBytes(StandardCharsets.US_ASCII);

		assertThatExceptionOfType(ExtractionException.class).isThrownBy(() -> extract(pdf, TextExtractor.PDF))
			.withMessageContaining("could not be read");
	}

	@Test
	void tooMuchTextIsRejected() {
		TextExtractor small = new TextExtractor(100);
		byte[] pdf = TestDocuments.pdf(List.of(List.of("x".repeat(80)), List.of("y".repeat(80))));
		byte[] text = "z".repeat(101).getBytes(StandardCharsets.UTF_8);

		assertThatExceptionOfType(ExtractionException.class)
			.isThrownBy(() -> small.extract(file(pdf), TextExtractor.PDF))
			.withMessageContaining("more than 100 characters");
		assertThatExceptionOfType(ExtractionException.class)
			.isThrownBy(() -> small.extract(file(text), TextExtractor.TEXT))
			.withMessageContaining("more than 100 characters");
	}

	@Test
	void unknownContentTypeIsRejected() {
		assertThatExceptionOfType(ExtractionException.class)
			.isThrownBy(() -> extract(new byte[] { 1 }, "application/x-msdownload"));
	}

	@Test
	void normalizeKeepsParagraphsButCollapsesNoise() {
		assertThat(TextExtractor.normalize("  a   b\n\n\n\nc \n d  ")).isEqualTo("a b\n\nc\nd");
	}

	private ExtractedDocument extract(byte[] content, String contentType) {
		return this.extractor.extract(file(content), contentType);
	}

	private Path file(byte[] content) {
		try {
			return Files.write(Files.createTempFile(this.tempDir, "doc-", ".bin"), content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
