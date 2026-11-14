package dev.docquery.api.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RAG evaluation set ({@code /evaluation/eval-set.json}): questions over the sample documents,
 * the pages that answer them, and checks on the answer. Schema: see {@code /evaluation/README.md}.
 */
record EvaluationSet(String description, int version, List<Case> cases) {

	static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	static final Path DEFAULT_LOCATION = REPO.resolve("evaluation").resolve("eval-set.json");

	static final String ANSWER = "answer";

	static final String REFUSAL = "refusal";

	EvaluationSet {
		cases = List.copyOf(cases);
		Set<String> ids = new HashSet<>();
		for (Case c : cases) {
			if (!ids.add(c.id())) {
				throw new IllegalArgumentException("Duplicate evaluation case id: " + c.id());
			}
		}
	}

	static EvaluationSet load(Path file) {
		try {
			return JsonMapper.builder()
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build()
				.readValue(Files.readString(file), EvaluationSet.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * @param conversation earlier questions asked in the same conversation (follow-ups); not scored
	 * @param sources what should be retrieved; empty when the documents do not answer the question
	 */
	record Case(String id, String category, List<String> conversation, String question, String note,
			List<ExpectedSource> sources, AnswerCheck answer) {

		Case {
			if (id == null || category == null || question == null || answer == null) {
				throw new IllegalArgumentException("Evaluation cases need an id, category, question and answer");
			}
			conversation = (conversation != null) ? List.copyOf(conversation) : List.of();
			sources = (sources != null) ? List.copyOf(sources) : List.of();
			if (answer.expectsAnswer() && sources.isEmpty()) {
				throw new IllegalArgumentException(id + ": an answerable case needs its expected sources");
			}
		}

		boolean answerable() {
			return this.answer.expectsAnswer();
		}

	}

	/**
	 * @param pages 1-based pages that answer the question; empty for unpaginated documents (Markdown, text)
	 * or when any page of the document counts
	 */
	record ExpectedSource(String document, List<Integer> pages) {

		ExpectedSource {
			pages = (pages != null) ? List.copyOf(pages) : List.of();
		}

		boolean matches(String documentName, Integer page) {
			return this.document.equals(documentName) && (this.pages.isEmpty() || this.pages.contains(page));
		}

	}

	/**
	 * @param expect {@code answer} (default) or {@code refusal} ("I couldn't find this in your documents")
	 * @param mustInclude each entry must appear in the answer (case-insensitive); an entry that is a list
	 * is satisfied by any one of its alternatives
	 * @param mustNotInclude none of these may appear: facts from another document or entity, injected
	 * text, padding sections
	 * @param maxWords answers longer than this are padded
	 */
	record AnswerCheck(String expect, List<JsonNode> mustInclude, List<String> mustNotInclude, Integer maxWords) {

		AnswerCheck {
			expect = (expect != null) ? expect : ANSWER;
			if (!ANSWER.equals(expect) && !REFUSAL.equals(expect)) {
				throw new IllegalArgumentException("answer.expect must be 'answer' or 'refusal': " + expect);
			}
			mustInclude = (mustInclude != null) ? List.copyOf(mustInclude) : List.of();
			mustNotInclude = (mustNotInclude != null) ? List.copyOf(mustNotInclude) : List.of();
		}

		boolean expectsAnswer() {
			return ANSWER.equals(this.expect);
		}

		/** {@code mustInclude} as groups of alternatives. */
		List<List<String>> requiredGroups() {
			List<List<String>> groups = new ArrayList<>();
			for (JsonNode entry : this.mustInclude) {
				if (entry.isArray()) {
					List<String> alternatives = new ArrayList<>();
					entry.forEach(alternative -> alternatives.add(alternative.asString()));
					groups.add(List.copyOf(alternatives));
				}
				else {
					groups.add(List.of(entry.asString()));
				}
			}
			return List.copyOf(groups);
		}

	}

}
