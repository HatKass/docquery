package dev.docquery.worker.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.worker.chunking.Chunk;
import dev.docquery.worker.chunking.TextChunker;
import dev.docquery.worker.extraction.ExtractedDocument;
import dev.docquery.worker.extraction.PageText;
import dev.docquery.worker.extraction.TextExtractor;

/**
 * Keeps the inputs of the RAG evaluation (api-service, {@code RagEvaluationTests}) in sync with the
 * code that produces them in production. Both outputs are committed:
 * <ol>
 * <li>the sample PDFs in {@code /sample-data}, built from {@code /sample-data/source/*.txt}
 * ({@value #PAGE_BREAK} separates pages);</li>
 * <li>{@code /evaluation/fixtures/sample-data-chunks.json}: what this worker's real extractor and
 * chunker, with the production settings of {@code application.yml}, make of every evaluation
 * document. The api-service evaluation indexes these chunks, so it measures retrieval on exactly what
 * an upload would produce, without running the worker.</li>
 * </ol>
 * The tests fail when an output is stale. Regenerate with:
 *
 * <pre>
 * ./mvnw test -Dtest=EvaluationFixtureTests -Devaluation.update=true
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvaluationFixtureTests {

	static final String PAGE_BREAK = "<<<PAGE>>>";

	static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	static final Path SAMPLE_DATA = REPO.resolve("sample-data");

	static final Path SOURCES = SAMPLE_DATA.resolve("source");

	static final Path EVALUATION_DOCUMENTS = REPO.resolve("evaluation").resolve("documents");

	static final Path CHUNK_FIXTURE = REPO.resolve("evaluation").resolve("fixtures").resolve("sample-data-chunks.json");

	static final boolean UPDATE = Boolean.getBoolean("evaluation.update");

	static final Pattern DEFAULT_VALUE = Pattern.compile("\\$\\{[A-Z_]+:(\\d+)}");

	@Test
	@Order(1)
	void samplePdfsMatchTheirSources() throws IOException {
		List<Path> sources = files(SOURCES, ".txt");
		assertThat(sources).as("sample PDF sources").isNotEmpty();
		List<String> stale = new ArrayList<>();
		for (Path source : sources) {
			List<String> pages = pages(Files.readString(source));
			Path pdf = SAMPLE_DATA.resolve(source.getFileName().toString().replaceFirst("\\.txt$", ".pdf"));
			if (Files.exists(pdf) && normalized(extract(pdf).pages()).equals(normalized(pages))) {
				continue;
			}
			if (UPDATE) {
				Files.write(pdf, pdf(pages, title(pages)));
				assertThat(normalized(extract(pdf).pages())).as(pdf.getFileName().toString())
					.isEqualTo(normalized(pages));
			}
			else {
				stale.add(pdf.getFileName().toString());
			}
		}
		assertThat(stale).as("PDFs that do not match their source (regenerate with -Devaluation.update=true)")
			.isEmpty();
	}

	/** Runs after the PDFs are checked (or rebuilt): they are among the documents chunked here. */
	@Test
	@Order(2)
	void chunkFixtureMatchesTheWorkersExtractionAndChunking() throws IOException {
		int maxCharacters = productionDefault("docquery.ingestion.chunking.max-characters");
		int overlap = productionDefault("docquery.ingestion.chunking.overlap-characters");
		TextChunker chunker = new TextChunker(maxCharacters, overlap);
		List<Map<String, Object>> documents = new ArrayList<>();
		for (Path file : evaluationDocuments()) {
			ExtractedDocument extracted = extract(file);
			List<Map<String, Object>> chunks = new ArrayList<>();
			for (Chunk chunk : chunker.chunk(extracted.pages())) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("index", chunk.index());
				json.put("page", chunk.pageNumber());
				json.put("start", chunk.startOffset());
				json.put("end", chunk.endOffset());
				json.put("content", chunk.content());
				chunks.add(json);
			}
			Map<String, Object> document = new LinkedHashMap<>();
			document.put("file", file.getFileName().toString());
			document.put("path", REPO.relativize(file).toString().replace('\\', '/'));
			document.put("contentType", contentType(file));
			document.put("pageCount", extracted.pageCount());
			document.put("chunks", chunks);
			documents.add(document);
		}
		Map<String, Object> fixture = new LinkedHashMap<>();
		fixture.put("comment", "Generated by ingestion-worker EvaluationFixtureTests; do not edit by hand.");
		Map<String, Object> chunking = new LinkedHashMap<>();
		chunking.put("maxCharacters", maxCharacters);
		chunking.put("overlapCharacters", overlap);
		fixture.put("chunking", chunking);
		fixture.put("documents", documents);
		String expected = JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.build()
			.writeValueAsString(fixture)
			.replace("\r\n", "\n") + "\n";

		String actual = Files.exists(CHUNK_FIXTURE) ? Files.readString(CHUNK_FIXTURE).replace("\r\n", "\n") : "";
		if (UPDATE && !actual.equals(expected)) {
			Files.createDirectories(CHUNK_FIXTURE.getParent());
			Files.writeString(CHUNK_FIXTURE, expected);
			actual = expected;
		}
		assertThat(actual).as("%s is stale (regenerate with -Devaluation.update=true)", REPO.relativize(CHUNK_FIXTURE))
			.isEqualTo(expected);
	}

	/** Sample documents (not their sources) and the evaluation-only documents, in a stable order. */
	static List<Path> evaluationDocuments() throws IOException {
		List<Path> all = new ArrayList<>();
		all.addAll(files(SAMPLE_DATA, ".pdf", ".md", ".txt", ".docx"));
		all.addAll(files(EVALUATION_DOCUMENTS, ".pdf", ".md", ".txt", ".docx"));
		return all.stream().filter(p -> !p.getFileName().toString().equalsIgnoreCase("README.md")).toList();
	}

	private static List<Path> files(Path directory, String... extensions) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(Files::isRegularFile)
				.filter(p -> Arrays.stream(extensions).anyMatch(e -> p.getFileName().toString().endsWith(e)))
				.sorted()
				.toList();
		}
	}

	static String contentType(Path file) {
		String name = file.getFileName().toString();
		if (name.endsWith(".pdf")) {
			return TextExtractor.PDF;
		}
		if (name.endsWith(".docx")) {
			return TextExtractor.DOCX;
		}
		return name.endsWith(".md") ? TextExtractor.MARKDOWN : TextExtractor.TEXT;
	}

	private static ExtractedDocument extract(Path file) {
		return new TextExtractor(2_000_000).extract(file, contentType(file));
	}

	private static List<String> pages(String source) {
		return Arrays.stream(source.replace("\r\n", "\n").split(Pattern.quote(PAGE_BREAK) + "\n?")).toList();
	}

	private static String title(List<String> pages) {
		return pages.getFirst().lines().findFirst().orElse("Sample document");
	}

	/** Page texts compared word by word: PDF extraction does not keep line wrapping or blank lines. */
	private static List<String> normalized(List<?> pages) {
		return pages.stream()
			.map(p -> (p instanceof PageText page) ? page.text() : (String) p)
			.map(text -> text.replaceAll("\\s+", " ").strip())
			.toList();
	}

	/** The default of a {@code ${ENV:default}} setting in the worker's main application.yml. */
	private static int productionDefault(String property) throws IOException {
		PropertySource<?> yml = new YamlPropertySourceLoader()
			.load("application.yml", new ClassPathResource("application.yml"))
			.getFirst();
		Object value = yml.getProperty(property);
		Matcher matcher = DEFAULT_VALUE.matcher(String.valueOf(value));
		assertThat(matcher.matches()).as("%s = %s", property, value).isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	/** A plain, text-only PDF: Helvetica 10 pt, lines wrapped to the page width. */
	static byte[] pdf(List<String> pages, String title) {
		try (PDDocument document = new PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
			float fontSize = 10;
			float margin = 56;
			float width = PDRectangle.A4.getWidth() - 2 * margin;
			for (String text : pages) {
				PDPage page = new PDPage(PDRectangle.A4);
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setLeading(fontSize * 1.4f);
					content.newLineAtOffset(margin, PDRectangle.A4.getHeight() - margin);
					boolean first = (document.getNumberOfPages() == 1);
					for (String line : text.strip().split("\n", -1)) {
						PDType1Font lineFont = first ? bold : font;
						float lineSize = first ? 14 : fontSize;
						content.setFont(lineFont, lineSize);
						for (String wrapped : wrap(line, lineFont, lineSize, width)) {
							content.showText(wrapped);
							content.newLine();
						}
						first = false;
					}
					content.endText();
				}
			}
			PDDocumentInformation info = new PDDocumentInformation();
			info.setTitle(title);
			info.setAuthor("DocQuery sample data (fictional)");
			document.setDocumentInformation(info);
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static List<String> wrap(String line, PDType1Font font, float size, float width) throws IOException {
		if (line.isBlank()) {
			return List.of("");
		}
		String indent = line.replaceFirst("^(\\s*).*$", "$1");
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : line.strip().split(" ")) {
			String candidate = current.isEmpty() ? indent + word : current + " " + word;
			if (!current.isEmpty() && font.getStringWidth(candidate) / 1000 * size > width) {
				lines.add(current.toString());
				current = new StringBuilder(indent + word);
			}
			else {
				current = new StringBuilder(candidate);
			}
		}
		lines.add(current.toString());
		return lines;
	}

}
