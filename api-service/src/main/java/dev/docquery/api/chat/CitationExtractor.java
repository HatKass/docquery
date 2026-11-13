package dev.docquery.api.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks what the model wrote against what it was given. Citation markers ({@code [2]},
 * {@code [1, 3]}) are kept only if they name a source that was actually sent; others (a hallucinated
 * {@code [7]}, or one a malicious document asked for) are removed from the text. The citations shown
 * to the user are then built from the retrieved chunks themselves, so the model can never invent a
 * document name, page or quote.
 */
final class CitationExtractor {

	private static final Pattern MARKER = Pattern.compile("\\[\\s*(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)\\s*]");

	private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("[ \\t]+([.,;:!?])");

	private static final Pattern DOUBLE_SPACES = Pattern.compile("[ \\t]{2,}");

	/**
	 * @param content the answer with invalid markers removed
	 * @param sourceNumbers the valid source numbers cited, ascending
	 */
	record Extracted(String content, List<Integer> sourceNumbers) {
	}

	private CitationExtractor() {
	}

	static Extracted extract(String answer, int sourceCount) {
		String text = answer.strip();
		if (isNotFound(text)) {
			return new Extracted(GroundedPrompt.NOT_FOUND_ANSWER, List.of());
		}
		SortedSet<Integer> cited = new TreeSet<>();
		Matcher matcher = MARKER.matcher(text);
		StringBuilder sb = new StringBuilder();
		boolean changed = false;
		while (matcher.find()) {
			List<String> valid = new ArrayList<>();
			for (String part : matcher.group(1).split(",")) {
				int number = Integer.parseInt(part.strip());
				if (number >= 1 && number <= sourceCount) {
					cited.add(number);
					valid.add(Integer.toString(number));
				}
			}
			String replacement = valid.isEmpty() ? "" : "[" + String.join(", ", valid) + "]";
			changed |= !replacement.equals(matcher.group());
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(sb);
		String content = sb.toString();
		if (changed) {
			content = DOUBLE_SPACES.matcher(SPACE_BEFORE_PUNCTUATION.matcher(content).replaceAll("$1")).replaceAll(" ")
				.strip();
		}
		return new Extracted(content, List.copyOf(cited));
	}

	/**
	 * The model was told to reply with exactly the not-found sentence; small models sometimes add a
	 * few words or vary the apostrophe. Treat a short reply that starts with it as that answer.
	 */
	static boolean isNotFound(String answer) {
		String normalized = answer.strip().replace('’', '\'').toLowerCase(Locale.ROOT);
		String expected = GroundedPrompt.NOT_FOUND_ANSWER.toLowerCase(Locale.ROOT);
		String withoutDot = expected.substring(0, expected.length() - 1);
		return normalized.startsWith(withoutDot) && normalized.length() <= expected.length() + 80;
	}

}
