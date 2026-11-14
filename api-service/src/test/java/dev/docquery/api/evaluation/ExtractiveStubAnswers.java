package dev.docquery.api.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.prompt.Prompt;

import dev.docquery.api.chat.ChatTestAccess;

/**
 * A deterministic stand-in for a chat model in the CI evaluation: it reads the grounded prompt like a
 * model would (the {@code <source>} blocks and the question) and answers with the source sentence that
 * shares the most words with the question, cited. Below {@value #MIN_OVERLAP} shared words it gives
 * DocQuery's "not found" reply.
 * <p>
 * It is no substitute for a model's judgment (it is not injection-proof and cannot assess a CV); it
 * makes the CI run exercise the whole pipeline (prompt, streaming, citations, storage, scoring) with
 * stable, free answers, so a change in retrieval or prompting shows up as a change in the scores.
 */
final class ExtractiveStubAnswers {

	static final int MIN_OVERLAP = 2;

	private static final Pattern SOURCE = Pattern
		.compile("<source id=\"(\\d+)\" document=\"[^\"]*\"(?: page=\"\\d+\")?>\\n(.*?)\\n</source>", Pattern.DOTALL);

	private static final Pattern QUESTION = Pattern.compile("\\nQuestion: (.*?)\\n\\n", Pattern.DOTALL);

	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");

	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

	private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "are", "what", "how", "many", "much",
			"does", "did", "can", "who", "which", "when", "where", "why", "with", "from", "that", "this", "there",
			"their", "her", "his", "she", "its", "your", "you", "our", "per", "get", "has", "have", "about", "into",
			"any", "all", "after", "must", "should", "will", "would", "may", "mean", "tell", "need", "long");

	private ExtractiveStubAnswers() {
	}

	static List<String> reply(Prompt prompt) {
		String userTurn = prompt.getInstructions().getLast().getText();
		Matcher questionMatcher = QUESTION.matcher(userTurn);
		Set<String> questionWords = questionMatcher.find() ? words(questionMatcher.group(1)) : Set.of();
		List<Candidate> candidates = new ArrayList<>();
		Matcher source = SOURCE.matcher(userTurn);
		while (source.find()) {
			int id = Integer.parseInt(source.group(1));
			for (String sentence : SENTENCE_END.split(source.group(2))) {
				Set<String> shared = new LinkedHashSet<>(words(sentence));
				shared.retainAll(questionWords);
				if (!sentence.isBlank()) {
					candidates.add(new Candidate(id, sentence.strip(), shared.size()));
				}
			}
		}
		// Most shared words first; ties: earlier source, then earlier sentence (stable sort).
		candidates.sort(Comparator.comparingInt(Candidate::overlap).reversed().thenComparingInt(Candidate::source));
		if (candidates.isEmpty() || candidates.getFirst().overlap() < MIN_OVERLAP) {
			return List.of(ChatTestAccess.notFoundAnswer());
		}
		Candidate best = candidates.getFirst();
		List<String> pieces = new ArrayList<>(List.of(trimEnd(best.sentence()), " [" + best.source() + "]."));
		// A second fact from another source, for questions with two parts.
		candidates.stream()
			.filter(c -> c.source() != best.source() && c.overlap() >= MIN_OVERLAP && c.overlap() >= best.overlap() - 1)
			.findFirst()
			.ifPresent(c -> pieces.addAll(List.of(" " + trimEnd(c.sentence()), " [" + c.source() + "].")));
		return List.copyOf(pieces);
	}

	/**
	 * Content words, lower case, crudely stemmed to their first {@value #STEM} letters, so "days" matches
	 * "day" and "internship" matches "intern".
	 */
	static Set<String> words(String text) {
		Set<String> words = new LinkedHashSet<>();
		Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (matcher.find()) {
			String word = matcher.group();
			if (word.length() > 2 && !STOP_WORDS.contains(word)) {
				String singular = (word.length() > 3 && word.endsWith("s")) ? word.substring(0, word.length() - 1) : word;
				words.add(singular.substring(0, Math.min(STEM, singular.length())));
			}
		}
		return words;
	}

	static final int STEM = 6;

	private static String trimEnd(String sentence) {
		return sentence.replaceAll("[.!?:;,\\s]+$", "");
	}

	private record Candidate(int source, String sentence, int overlap) {
	}

}
