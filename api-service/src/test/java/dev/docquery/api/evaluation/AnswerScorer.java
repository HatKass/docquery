package dev.docquery.api.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.docquery.api.evaluation.EvaluationSet.AnswerCheck;
import dev.docquery.api.evaluation.EvaluationSet.Case;
import dev.docquery.api.evaluation.EvaluationSet.ExpectedSource;

/**
 * Scores one answer against its case: deterministic string checks, no model judges, so results are
 * reproducible and cheap. The checks target the failure modes found in manual testing:
 * <ul>
 * <li><b>false not-found</b>: the answer declines (without citing anything) although the documents
 * answer the question;</li>
 * <li><b>attribution</b>: a fact from one document attributed to another entity ({@code mustNotInclude});</li>
 * <li><b>padding</b>: a factual answer longer than {@code maxWords}, or with assessment sections;</li>
 * <li><b>citations</b>: an answer must cite, and only the documents that hold the answer;</li>
 * <li><b>refusal</b>: unanswerable questions must be declined, not guessed.</li>
 * </ul>
 */
final class AnswerScorer {

	/** Phrases that mean "the documents do not say" (DocQuery's exact reply first). */
	static final List<String> REFUSAL_MARKERS = List.of("couldn't find this in your documents", "could not find",
			"couldn't find", "not mentioned", "isn't mentioned", "is not mentioned", "does not mention",
			"doesn't mention", "do not mention", "don't mention", "no information", "not specified",
			"does not specify", "doesn't specify", "do not specify", "don't specify", "does not contain",
			"doesn't contain", "do not contain", "don't contain", "not provided", "does not provide",
			"doesn't provide", "do not provide", "don't provide", "does not say", "doesn't say", "do not say",
			"don't say", "not stated", "does not state", "doesn't state", "do not state", "don't state",
			"not covered", "do not cover", "don't cover", "does not cover", "doesn't cover", "not available in");

	private static final Pattern CITATION = Pattern.compile("\\[\\d+(?:\\s*,\\s*\\d+)*]");

	private static final Pattern MARKDOWN = Pattern.compile("[*_`#>]");

	private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

	private AnswerScorer() {
	}

	/** One cited source, as the API reports it. */
	record Citation(String documentName, Integer page) {
	}

	/**
	 * @param refusal the answer says (in whole or in part) that the documents do not cover the question
	 * @param falseNotFound an answerable question was declined without giving the expected facts
	 * @param failures human-readable reasons; empty when the answer passes
	 */
	record Score(boolean passed, boolean refusal, boolean falseNotFound, boolean includesRequired,
			boolean excludesForbidden, boolean withinLength, boolean citationsCorrect, int words,
			List<String> failures) {
	}

	static Score score(Case testCase, String answer, List<Citation> citations) {
		AnswerCheck check = testCase.answer();
		String text = (answer != null) ? answer : "";
		String lower = normalize(text);
		boolean refusal = isRefusal(text);
		List<String> failures = new ArrayList<>();

		List<List<String>> missing = check.requiredGroups()
			.stream()
			.filter(group -> group.stream().noneMatch(alternative -> contains(lower, alternative)))
			.toList();
		boolean includesRequired = missing.isEmpty();
		List<String> forbidden = check.mustNotInclude()
			.stream()
			.filter(phrase -> contains(lower, phrase))
			.toList();
		boolean excludesForbidden = forbidden.isEmpty();
		int words = wordCount(text);
		boolean withinLength = check.maxWords() == null || words <= check.maxWords();
		boolean falseNotFound = false;
		boolean citationsCorrect = true;

		if (check.expectsAnswer()) {
			if (!includesRequired) {
				// A plain decline (no cited facts), not a partial answer with a "missing information" note.
				falseNotFound = refusal && citations.isEmpty();
				failures.add(falseNotFound ? "false not-found: declined although the documents answer it"
						: "missing " + missing.stream().map(g -> String.join(" / ", g)).collect(Collectors.joining(", ")));
			}
			if (!withinLength) {
				failures.add("padded: " + words + " words (max " + check.maxWords() + ")");
			}
			Set<String> expectedDocuments = testCase.sources()
				.stream()
				.map(ExpectedSource::document)
				.collect(Collectors.toSet());
			List<String> foreign = citations.stream()
				.map(Citation::documentName)
				.filter(name -> !expectedDocuments.contains(name))
				.distinct()
				.toList();
			if (citations.isEmpty()) {
				citationsCorrect = false;
				failures.add("no citation");
			}
			else if (!foreign.isEmpty()) {
				citationsCorrect = false;
				failures.add("cites other documents: " + String.join(", ", foreign));
			}
		}
		else if (!refusal) {
			failures.add("answered instead of saying the documents do not cover it");
		}
		if (!excludesForbidden) {
			failures.add("contains forbidden text: " + String.join(", ", forbidden));
		}
		return new Score(failures.isEmpty(), refusal, falseNotFound, includesRequired, excludesForbidden,
				withinLength, citationsCorrect, words, List.copyOf(failures));
	}

	/**
	 * Case-insensitive; a number only matches as a whole number ("25" is not found in "2025" or "250").
	 */
	static boolean contains(String normalizedText, String term) {
		String needle = normalize(term);
		if (NUMBER.matcher(needle).matches()) {
			return Pattern.compile("(?<![\\d.,])" + Pattern.quote(needle) + "(?![\\d]|[.,]\\d)")
				.matcher(normalizedText)
				.find();
		}
		return normalizedText.contains(needle);
	}

	static boolean isRefusal(String answer) {
		String lower = normalize(answer);
		return REFUSAL_MARKERS.stream().anyMatch(lower::contains);
	}

	/** Words of the answer as a reader sees it: without citation markers and Markdown symbols. */
	static int wordCount(String answer) {
		String plain = MARKDOWN.matcher(CITATION.matcher(answer).replaceAll(" ")).replaceAll(" ").strip();
		// Only tokens with a letter or digit: list dashes and stray punctuation are not words.
		return plain.isEmpty() ? 0
				: (int) java.util.Arrays.stream(plain.split("\\s+")).filter(w -> w.matches(".*[\\p{L}\\p{N}].*")).count();
	}

	/** Lower case, typographic apostrophes and non-breaking spaces made plain. */
	static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replace('’', '\'').replace(' ', ' ').replace(' ', ' ');
	}

}
