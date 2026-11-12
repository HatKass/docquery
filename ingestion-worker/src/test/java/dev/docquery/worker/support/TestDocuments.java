package dev.docquery.worker.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Real PDF and DOCX files built in memory (PDFBox comes with Tika; DOCX is a hand-written OOXML package). */
public final class TestDocuments {

	private TestDocuments() {
	}

	/** One PDF page per entry; each entry is a list of lines. */
	public static byte[] pdf(List<List<String>> pages) {
		return pdf(pages, null);
	}

	public static byte[] encryptedPdf(String text) {
		return pdf(List.of(List.of(text)), "secret");
	}

	private static byte[] pdf(List<List<String>> pages, String password) {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (List<String> lines : pages) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(font, 11);
					content.setLeading(14);
					content.newLineAtOffset(50, 720);
					for (String line : lines) {
						content.showText(line);
						content.newLine();
					}
					content.endText();
				}
			}
			if (password != null) {
				StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-" + password, password,
						new AccessPermission());
				policy.setEncryptionKeyLength(128);
				document.protect(policy);
			}
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** A minimal but complete Word package: content types, package relationships, main document. */
	public static byte[] docx(List<String> paragraphs) {
		StringBuilder body = new StringBuilder();
		for (String paragraph : paragraphs) {
			body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escape(paragraph)).append("</w:t></w:r></w:p>");
		}
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("[Content_Types].xml", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
				  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
				  <Default Extension="xml" ContentType="application/xml"/>
				  <Override PartName="/word/document.xml"
				    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
				</Types>
				""");
		entries.put("_rels/.rels", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
				  <Relationship Id="rId1" Target="word/document.xml"
				    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>
				</Relationships>
				""");
		entries.put("word/document.xml", """
				<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>%s</w:body></w:document>
				""".formatted(body));
		try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().strip().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
			zip.finish();
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	public static byte[] text(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

}
