package dev.docquery.worker.extraction;

import java.util.List;

/**
 * Text of a document, page by page.
 *
 * @param pageCount number of pages for paginated formats (PDF), {@code null} otherwise
 */
public record ExtractedDocument(List<PageText> pages, Integer pageCount) {

	public ExtractedDocument {
		pages = List.copyOf(pages);
	}

	public boolean hasText() {
		return this.pages.stream().anyMatch(page -> !page.text().isBlank());
	}

	public int characterCount() {
		return this.pages.stream().mapToInt(page -> page.text().length()).sum();
	}

}
