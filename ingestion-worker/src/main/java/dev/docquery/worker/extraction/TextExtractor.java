package dev.docquery.worker.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.xml.sax.SAXException;

/**
 * Turns a stored file into page-aware plain text.
 * <ul>
 * <li>PDF: Apache Tika (PDFBox), one {@link PageText} per page with its real page number.</li>
 * <li>DOCX: Apache Tika (POI). Word files have no fixed pages (pagination depends on the
 * renderer), so the text is one unpaginated block; citations then point to the document.</li>
 * <li>TXT / Markdown: decoded as UTF-8 (api-service already verified the encoding).</li>
 * </ul>
 * The parser is chosen from the content type detected at upload, never guessed again, so a file can
 * only be read by the parser of the type it was validated as.
 * <p>
 * Input is a local file, so every failure here is about the content (damaged, encrypted, too
 * long...) and reported as {@link ExtractionException}; storage problems happen before, when the
 * file is downloaded, and stay retryable.
 */
public class TextExtractor {

	public static final String PDF = "application/pdf";

	public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	public static final String TEXT = "text/plain";

	public static final String MARKDOWN = "text/markdown";

	private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

	private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");

	private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" ?\\n ?");

	private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

	private final int maxCharacters;

	public TextExtractor(int maxCharacters) {
		this.maxCharacters = maxCharacters;
	}

	public ExtractedDocument extract(Path file, String contentType) {
		return switch (contentType) {
			case PDF -> parse(new PDFParser(), file, true);
			case DOCX -> parse(new OOXMLParser(), file, false);
			case TEXT, MARKDOWN -> plainText(file);
			default -> throw new ExtractionException("Files of type '" + contentType + "' cannot be processed.");
		};
	}

	private ExtractedDocument parse(Parser parser, Path file, boolean paginated) {
		PageCollectingHandler handler = new PageCollectingHandler(paginated, this.maxCharacters);
		try (TikaInputStream tika = TikaInputStream.get(file)) {
			parser.parse(tika, handler, new Metadata(), new ParseContext());
		}
		catch (EncryptedDocumentException ex) {
			throw new ExtractionException(
					"The document is password-protected. Remove the password and upload it again.", ex);
		}
		catch (TikaException | SAXException | IOException | RuntimeException ex) {
			if (causedByLimit(ex)) {
				throw tooMuchText(ex);
			}
			throw new ExtractionException("The document could not be read. The file may be damaged.", ex);
		}
		List<String> raw = handler.pages();
		List<PageText> pages = new ArrayList<>(raw.size());
		for (int i = 0; i < raw.size(); i++) {
			pages.add(new PageText(paginated ? i + 1 : null, normalize(raw.get(i))));
		}
		return new ExtractedDocument(pages, paginated ? pages.size() : null);
	}

	private ExtractedDocument plainText(Path file) {
		String text;
		try (InputStream in = Files.newInputStream(file)) {
			// UTF-8 needs at most 4 bytes per character: reading up to that bound tells whether the
			// limit is exceeded without loading an unbounded file into memory.
			long maxBytes = 4L * this.maxCharacters;
			byte[] bytes = in.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE - 8));
			text = new String(bytes, StandardCharsets.UTF_8);
			if (text.length() > this.maxCharacters || in.read() != -1) {
				throw tooMuchText(null);
			}
		}
		catch (IOException ex) {
			throw new ExtractionException("The document could not be read.", ex);
		}
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		return new ExtractedDocument(List.of(new PageText(null, normalize(text))), null);
	}

	private static boolean causedByLimit(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof PageCollectingHandler.LimitReachedException) {
				return true;
			}
		}
		return false;
	}

	private ExtractionException tooMuchText(Throwable cause) {
		return new ExtractionException("The document contains more than " + this.maxCharacters
				+ " characters of text, the processing limit. Split it into smaller files.", cause);
	}

	/**
	 * Collapses runs of spaces, trims lines, keeps at most one blank line between paragraphs and
	 * strips control characters. Chunk offsets refer to this normalized text.
	 */
	static String normalize(String text) {
		String result = text.replace("\r\n", "\n").replace('\r', '\n').replace('\f', '\n');
		result = CONTROL_CHARACTERS.matcher(result).replaceAll("");
		result = HORIZONTAL_SPACE.matcher(result).replaceAll(" ");
		result = SPACE_AROUND_NEWLINE.matcher(result).replaceAll("\n");
		result = BLANK_LINES.matcher(result).replaceAll("\n\n");
		return result.strip();
	}

}
