package dev.docquery.worker.extraction;

/**
 * Normalized text of one page.
 *
 * @param pageNumber 1-based page number, or {@code null} for formats without pages (DOCX, TXT, MD),
 * which are extracted as one unpaginated block
 */
public record PageText(Integer pageNumber, String text) {

}
