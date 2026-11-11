package dev.docquery.api.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tiny but structurally real files for upload tests. Every call embeds a random marker, so two calls
 * never produce the same bytes (uploads are de-duplicated by content hash).
 */
public final class SampleFiles {

	private SampleFiles() {
	}

	public static byte[] pdf() {
		return ("%PDF-1.4\n% " + UUID.randomUUID() + "\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n")
			.getBytes(StandardCharsets.US_ASCII);
	}

	public static byte[] docx() {
		return zip(Map.of("[Content_Types].xml", "<Types/>", "word/document.xml",
				"<w:document><w:body><w:p><w:r><w:t>" + UUID.randomUUID() + "</w:t></w:r></w:p></w:body></w:document>"));
	}

	/** A valid ZIP that is not a Word document (e.g. a spreadsheet). */
	public static byte[] xlsxLikeZip() {
		return zip(Map.of("[Content_Types].xml", "<Types/>", "xl/workbook.xml", "<workbook/>"));
	}

	public static byte[] text(String content) {
		return (content + "\n" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] zip(Map<String, String> entries) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

}
