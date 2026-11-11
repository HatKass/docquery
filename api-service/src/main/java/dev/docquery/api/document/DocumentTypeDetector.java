package dev.docquery.api.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import dev.docquery.api.common.error.ApiException;

/**
 * Decides what an uploaded file really is from its <em>content</em>, then checks that it matches the
 * extension. The client's {@code Content-Type} header is never trusted.
 * <ul>
 * <li>PDF: the {@code %PDF-} signature within the first 1 KiB (where readers look for it).</li>
 * <li>DOCX: a ZIP container with {@code [Content_Types].xml} and {@code word/document.xml}. Only the
 * ZIP's central directory is read, nothing is decompressed, so a ZIP bomb costs nothing here.</li>
 * <li>TXT / Markdown: strictly valid UTF-8 with no binary control characters, and not starting with
 * a PDF signature.</li>
 * </ul>
 * Anything else (executables, images, legacy .doc, a PDF renamed to .txt...) is rejected with 415.
 */
@Component
class DocumentTypeDetector {

	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] ZIP_SIGNATURE = { 'P', 'K', 3, 4 };

	private static final int PDF_SIGNATURE_WINDOW = 1024;

	private static final String SUPPORTED = "Upload PDF, Word (.docx), plain text (.txt) or Markdown (.md) files.";

	DocumentType detect(String filename, Path file) throws IOException {
		DocumentType expected = DocumentType.forExtension(extension(filename))
			.orElseThrow(() -> unsupported("unsupported-file-type",
					"'" + filename + "' is not a supported file type. " + SUPPORTED));
		boolean matches = switch (expected) {
			case PDF -> isPdf(file);
			case DOCX -> isDocx(file);
			// A file that starts like a PDF is a PDF, even if it happens to be all ASCII.
			case TEXT, MARKDOWN -> !startsWith(file, PDF_SIGNATURE) && isUtf8Text(filename, file);
		};
		if (!matches) {
			throw unsupported("file-content-mismatch", "The content of '" + filename + "' is not a valid "
					+ describe(expected) + " file. The file may be damaged or have the wrong extension.");
		}
		return expected;
	}

	static String extension(String filename) {
		int dot = filename.lastIndexOf('.');
		return (dot < 0) ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static boolean isPdf(Path file) throws IOException {
		byte[] head = readHead(file, PDF_SIGNATURE_WINDOW);
		for (int i = 0; i + PDF_SIGNATURE.length <= head.length; i++) {
			if (Arrays.equals(head, i, i + PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDocx(Path file) throws IOException {
		if (!startsWith(file, ZIP_SIGNATURE)) {
			return false;
		}
		try (ZipFile zip = new ZipFile(file.toFile())) {
			return zip.getEntry("[Content_Types].xml") != null && zip.getEntry("word/document.xml") != null;
		}
		catch (ZipException ex) {
			return false;
		}
	}

	private static boolean isUtf8Text(String filename, Path file) throws IOException {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		char[] buffer = new char[8192];
		try (InputStream in = Files.newInputStream(file); Reader reader = new InputStreamReader(in, decoder)) {
			int read;
			while ((read = reader.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					if (isBinaryControl(buffer[i])) {
						return false;
					}
				}
			}
			return true;
		}
		catch (CharacterCodingException ex) {
			throw unsupported("unsupported-text-encoding",
					"'" + filename + "' is not UTF-8 text. Save it with UTF-8 encoding and upload it again.");
		}
	}

	/** NUL and other C0 controls never appear in real text files; tab, LF, CR and form feed do. */
	private static boolean isBinaryControl(char c) {
		return c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f';
	}

	private static boolean startsWith(Path file, byte[] signature) throws IOException {
		return Arrays.equals(readHead(file, signature.length), signature);
	}

	private static byte[] readHead(Path file, int maxBytes) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return in.readNBytes(maxBytes);
		}
	}

	private static String describe(DocumentType type) {
		return switch (type) {
			case PDF -> "PDF";
			case DOCX -> "Word (.docx)";
			case TEXT -> "plain text";
			case MARKDOWN -> "Markdown";
		};
	}

	private static ApiException unsupported(String code, String detail) {
		return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, code, "Unsupported file", detail);
	}

}
