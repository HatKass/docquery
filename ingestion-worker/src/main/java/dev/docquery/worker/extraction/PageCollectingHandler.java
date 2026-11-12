package dev.docquery.worker.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Collects the plain text of Tika's XHTML output, split into pages.
 * <p>
 * Tika's PDF parser wraps every page in {@code <div class="page">}; text inside such a div belongs
 * to that page (nested divs, e.g. annotations, stay on it). For unpaginated formats everything goes
 * into one block. Block-level elements become line breaks, so paragraphs survive for chunking.
 * Stops with {@link LimitReachedException} once {@code maxCharacters} have been collected.
 */
class PageCollectingHandler extends DefaultHandler {

	private static final Set<String> BLOCK_ELEMENTS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li",
			"tr", "br", "pre", "blockquote", "table", "ul", "ol", "dt", "dd");

	private static final Set<String> CELL_ELEMENTS = Set.of("td", "th");

	static final class LimitReachedException extends SAXException {

		LimitReachedException() {
			super("Character limit reached");
		}

	}

	private final boolean paginated;

	private final int maxCharacters;

	private final List<StringBuilder> pages = new ArrayList<>();

	private StringBuilder current;

	private int divDepth;

	/** Div depth at which the current page started, or -1 outside a page. */
	private int pageDivDepth = -1;

	private int headDepth;

	private int collected;

	PageCollectingHandler(boolean paginated, int maxCharacters) {
		this.paginated = paginated;
		this.maxCharacters = maxCharacters;
		if (!paginated) {
			this.current = new StringBuilder();
			this.pages.add(this.current);
		}
	}

	/** Raw page texts, in document order (not yet normalized). */
	List<String> pages() {
		return this.pages.stream().map(StringBuilder::toString).toList();
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes attributes) {
		String name = name(localName, qName);
		if ("head".equals(name)) {
			this.headDepth++;
		}
		if ("div".equals(name)) {
			this.divDepth++;
			if (this.paginated && this.pageDivDepth < 0 && "page".equals(attributes.getValue("class"))) {
				this.pageDivDepth = this.divDepth;
				this.current = new StringBuilder();
				this.pages.add(this.current);
			}
		}
		if (BLOCK_ELEMENTS.contains(name)) {
			append('\n');
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) {
		String name = name(localName, qName);
		if ("head".equals(name)) {
			this.headDepth--;
		}
		if (BLOCK_ELEMENTS.contains(name)) {
			append('\n');
		}
		else if (CELL_ELEMENTS.contains(name)) {
			append(' ');
		}
		if ("div".equals(name)) {
			if (this.divDepth == this.pageDivDepth) {
				this.pageDivDepth = -1;
				this.current = null;
			}
			this.divDepth--;
		}
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		if (this.headDepth > 0 || this.current == null) {
			// Document head (title, metadata) or, for PDFs, content outside any page.
			return;
		}
		if (this.collected + length > this.maxCharacters) {
			throw new LimitReachedException();
		}
		this.current.append(ch, start, length);
		this.collected += length;
	}

	@Override
	public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
		characters(ch, start, length);
	}

	private void append(char separator) {
		if (this.current != null && this.headDepth == 0) {
			this.current.append(separator);
		}
	}

	private static String name(String localName, String qName) {
		return (localName == null || localName.isEmpty()) ? qName : localName;
	}

}
