package dev.docquery.worker.chunking;

import java.util.ArrayList;
import java.util.List;

import dev.docquery.worker.extraction.PageText;

/**
 * Splits page text into overlapping chunks of at most {@code maxCharacters}.
 * <p>
 * Each chunk ends at the best natural boundary in its second half: a paragraph break, else a
 * sentence end or line break, else a space; only a single word longer than the whole window is cut
 * hard. The next chunk starts about {@code overlapCharacters} earlier, at a sentence or word start,
 * so a fact split across a boundary is still retrievable from one chunk. Chunks never cross pages,
 * so every chunk has exactly one page to cite.
 * <p>
 * Sizes are in characters, not model tokens: deterministic, provider-independent and fast. For
 * English text 1 token is about 4 characters, so the defaults (1200 / 200) are roughly 300 / 50
 * tokens, far below the context window of common embedding models.
 */
public class TextChunker {

	private final int maxCharacters;

	private final int overlapCharacters;

	public TextChunker(int maxCharacters, int overlapCharacters) {
		if (maxCharacters < 100) {
			throw new IllegalArgumentException("Chunk size must be at least 100 characters");
		}
		if (overlapCharacters < 0 || overlapCharacters >= maxCharacters / 2) {
			throw new IllegalArgumentException("Overlap must be between 0 and half the chunk size");
		}
		this.maxCharacters = maxCharacters;
		this.overlapCharacters = overlapCharacters;
	}

	public List<Chunk> chunk(List<PageText> pages) {
		List<Chunk> chunks = new ArrayList<>();
		for (PageText page : pages) {
			chunkPage(page, chunks);
		}
		return chunks;
	}

	private void chunkPage(PageText page, List<Chunk> chunks) {
		String text = page.text();
		int length = text.length();
		int start = skipWhitespace(text, 0);
		while (start < length) {
			int end = (length - start <= this.maxCharacters) ? length : breakPoint(text, start);
			int contentEnd = trimEnd(text, start, end);
			if (contentEnd > start) {
				chunks.add(new Chunk(chunks.size(), page.pageNumber(), start, contentEnd,
						text.substring(start, contentEnd)));
			}
			if (end >= length) {
				break;
			}
			start = skipWhitespace(text, nextStart(text, start, end));
		}
	}

	/** Where to end a chunk that starts at {@code start} and cannot take the rest of the page. */
	private int breakPoint(String text, int start) {
		int limit = start + this.maxCharacters;
		int earliest = start + this.maxCharacters / 2;
		int paragraph = text.lastIndexOf("\n\n", limit - 2);
		if (paragraph >= earliest) {
			return paragraph;
		}
		for (int i = limit; i > earliest; i--) {
			if (isSentenceEnd(text, i) || text.charAt(i - 1) == '\n') {
				return i;
			}
		}
		for (int i = limit; i > earliest; i--) {
			if (Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return limit;
	}

	/** Start of the next chunk: within the overlap window, preferably at a sentence start. */
	private int nextStart(String text, int start, int end) {
		int from = Math.max(end - this.overlapCharacters, start + 1);
		for (int i = from; i < end; i++) {
			if (isSentenceEnd(text, i) || text.charAt(i - 1) == '\n') {
				return i;
			}
		}
		for (int i = from; i < end; i++) {
			if (Character.isWhitespace(text.charAt(i - 1)) && !Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		// No boundary in the overlap window (one very long word): continue without overlap.
		return end;
	}

	/** Position {@code i} directly follows ".", "!" or "?" and is followed by whitespace or the end. */
	private static boolean isSentenceEnd(String text, int i) {
		if (i <= 0 || i > text.length()) {
			return false;
		}
		char previous = text.charAt(i - 1);
		boolean punctuation = previous == '.' || previous == '!' || previous == '?';
		return punctuation && (i == text.length() || Character.isWhitespace(text.charAt(i)));
	}

	private static int skipWhitespace(String text, int from) {
		int i = from;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}

	private static int trimEnd(String text, int start, int end) {
		int i = end;
		while (i > start && Character.isWhitespace(text.charAt(i - 1))) {
			i--;
		}
		return i;
	}

}
