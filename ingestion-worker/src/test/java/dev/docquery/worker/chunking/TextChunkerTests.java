package dev.docquery.worker.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dev.docquery.worker.extraction.PageText;

class TextChunkerTests {

	private final TextChunker chunker = new TextChunker(200, 40);

	@Test
	void shortPageIsOneChunk() {
		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(1, "Refunds are processed within 14 days.")));

		assertThat(chunks).singleElement().satisfies(chunk -> {
			assertThat(chunk.index()).isZero();
			assertThat(chunk.pageNumber()).isEqualTo(1);
			assertThat(chunk.content()).isEqualTo("Refunds are processed within 14 days.");
			assertThat(chunk.startOffset()).isZero();
			assertThat(chunk.endOffset()).isEqualTo(chunk.content().length());
		});
	}

	@Test
	void chunksRespectTheSizeLimitAndMatchTheirOffsets() {
		String text = sentences(60);

		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(3, text)));

		assertThat(chunks).hasSizeGreaterThan(5);
		for (Chunk chunk : chunks) {
			assertThat(chunk.content().length()).isLessThanOrEqualTo(200);
			assertThat(text.substring(chunk.startOffset(), chunk.endOffset())).isEqualTo(chunk.content());
			assertThat(chunk.content()).isEqualTo(chunk.content().strip());
			assertThat(chunk.pageNumber()).isEqualTo(3);
		}
		assertThat(chunks).extracting(Chunk::index).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
	}

	@Test
	void chunksEndAtSentenceBoundariesAndOverlapFromASentenceStart() {
		// Overlap longer than one sentence (~56 characters), so the overlap window always holds a sentence start.
		String text = sentences(30);

		List<Chunk> chunks = new TextChunker(200, 90).chunk(List.of(new PageText(1, text)));

		for (int i = 0; i + 1 < chunks.size(); i++) {
			Chunk current = chunks.get(i);
			Chunk next = chunks.get(i + 1);
			assertThat(current.content()).as("chunk %d ends a sentence", i).endsWith(".");
			assertThat(next.startOffset()).as("chunk %d overlaps chunk %d", i + 1, i).isLessThan(current.endOffset());
			assertThat(next.content()).as("chunk %d starts a sentence", i + 1).startsWith("Sentence");
		}
	}

	@Test
	void overlapFallsBackToAWordStartWhenNoSentenceStartsInTheWindow() {
		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(1, sentences(30))));

		for (int i = 0; i + 1 < chunks.size(); i++) {
			assertThat(chunks.get(i + 1).startOffset()).isLessThan(chunks.get(i).endOffset());
			assertThat(chunks.get(i + 1).content()).matches("^[A-Za-z0-9].*");
		}
	}

	@Test
	void everyPartOfTheTextIsCovered() {
		String text = sentences(40);

		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(1, text)));

		assertThat(chunks.getFirst().startOffset()).isZero();
		assertThat(chunks.getLast().endOffset()).isEqualTo(text.length());
		for (int i = 0; i + 1 < chunks.size(); i++) {
			assertThat(chunks.get(i + 1).startOffset()).isLessThanOrEqualTo(chunks.get(i).endOffset() + 1);
		}
	}

	@Test
	void prefersParagraphBreaks() {
		String first = "A".repeat(10) + " " + "first paragraph words ".repeat(5).strip() + ".";
		String text = first + "\n\n" + "Second paragraph. ".repeat(10).strip();

		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(1, text)));

		assertThat(chunks.getFirst().content()).isEqualTo(first);
	}

	@Test
	void neverSplitsWordsWhenThereIsSpace() {
		String text = IntStream.range(0, 200).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));

		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(1, text)));

		for (Chunk chunk : chunks) {
			assertThat(chunk.content()).matches("word\\d+( word\\d+)*");
		}
	}

	@Test
	void cutsAnOverlongWordAndStillTerminates() {
		String text = "x".repeat(1000);

		List<Chunk> chunks = this.chunker.chunk(List.of(new PageText(1, text)));

		assertThat(chunks).hasSize(5);
		assertThat(chunks.stream().mapToInt(c -> c.content().length()).sum()).isEqualTo(1000);
	}

	@Test
	void chunksNeverSpanPagesAndIndexesContinueAcrossPages() {
		List<Chunk> chunks = this.chunker
			.chunk(List.of(new PageText(1, "Page one text."), new PageText(2, "  "), new PageText(3, "Page three.")));

		assertThat(chunks).extracting(Chunk::pageNumber).containsExactly(1, 3);
		assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
		assertThat(chunks).extracting(Chunk::content).containsExactly("Page one text.", "Page three.");
	}

	@Test
	void unpaginatedTextHasNoPageNumber() {
		assertThat(this.chunker.chunk(List.of(new PageText(null, "Plain text."))))
			.singleElement()
			.extracting(Chunk::pageNumber)
			.isNull();
	}

	@Test
	void rejectsOverlapOfHalfTheChunkOrMore() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TextChunker(200, 100));
		assertThatIllegalArgumentException().isThrownBy(() -> new TextChunker(50, 10));
	}

	private static String sentences(int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> "Sentence " + i + " explains one small fact about the handbook.")
			.collect(Collectors.joining(" "));
	}

}
