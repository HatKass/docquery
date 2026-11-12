package dev.docquery.worker.chunking;

/**
 * A piece of a document small enough to embed and to quote as a citation.
 *
 * @param index position in the document, 0-based and contiguous
 * @param pageNumber page the chunk is on ({@code null} for unpaginated formats); chunks never span pages
 * @param startOffset first character within the page's normalized text (inclusive)
 * @param endOffset last character within the page's normalized text (exclusive)
 * @param content the chunk text, equal to {@code pageText.substring(startOffset, endOffset)}
 */
public record Chunk(int index, Integer pageNumber, int startOffset, int endOffset, String content) {

}
