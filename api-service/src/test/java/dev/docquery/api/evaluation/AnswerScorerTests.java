package dev.docquery.api.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.evaluation.AnswerScorer.Citation;
import dev.docquery.api.evaluation.AnswerScorer.Score;
import dev.docquery.api.evaluation.EvaluationSet.AnswerCheck;
import dev.docquery.api.evaluation.EvaluationSet.Case;
import dev.docquery.api.evaluation.EvaluationSet.ExpectedSource;

class AnswerScorerTests {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final List<ExpectedSource> HANDBOOK = List.of(new ExpectedSource("handbook.pdf", List.of(2)));

	static final List<Citation> HANDBOOK_CITED = List.of(new Citation("handbook.pdf", 2));

	@Test
	void aShortCitedAnswerWithTheFactPasses() {
		Score score = AnswerScorer.score(leaveCase(), "Employees get 25 days of paid leave [1].", HANDBOOK_CITED);

		assertThat(score.passed()).isTrue();
		assertThat(score.failures()).isEmpty();
	}

	@Test
	void numbersMatchOnlyAsWholeNumbers() {
		assertThat(AnswerScorer.contains("founded in 2025", "25")).isFalse();
		assertThat(AnswerScorer.contains("up to 250 gbp", "25")).isFalse();
		assertThat(AnswerScorer.contains("25.5 days", "25")).isFalse();
		assertThat(AnswerScorer.contains("(25 days)", "25")).isTrue();
		assertThat(AnswerScorer.contains("1,200 gbp", "1,200")).isTrue();
	}

	@Test
	void alternativesAreAnyOf() {
		Case c = new Case("x", "fact", null, "q", null, HANDBOOK,
				new AnswerCheck(null, List.of(node("[\"2 months\", \"two months\"]")), null, null));

		assertThat(AnswerScorer.score(c, "Two months [1].", HANDBOOK_CITED).passed()).isTrue();
		assertThat(AnswerScorer.score(c, "Three months [1].", HANDBOOK_CITED).failures())
			.containsExactly("missing 2 months / two months");
	}

	@Test
	void aPlainDeclineOfAnAnswerableQuestionIsAFalseNotFound() {
		Score score = AnswerScorer.score(leaveCase(), "I couldn't find this in your documents.", List.of());

		assertThat(score.falseNotFound()).isTrue();
		assertThat(score.passed()).isFalse();
	}

	@Test
	void aPartialAnswerWithAMissingInformationNoteIsNotAFalseNotFound() {
		Score score = AnswerScorer.score(leaveCase(),
				"Leave requests go through the HR portal [1]. The total is not specified.", HANDBOOK_CITED);

		assertThat(score.falseNotFound()).isFalse();
		assertThat(score.failures()).containsExactly("missing 25");
	}

	@Test
	void paddingForbiddenTextAndForeignCitationsAreReported() {
		String padded = "**Facts:** Employees get 25 days [1]. " + "More words here. ".repeat(30);

		Score score = AnswerScorer.score(leaveCase(), padded,
				List.of(new Citation("handbook.pdf", 2), new Citation("supplier-notes.md", null)));

		assertThat(score.withinLength()).isFalse();
		assertThat(score.failures()).anyMatch(f -> f.startsWith("padded: "))
			.contains("cites other documents: supplier-notes.md", "contains forbidden text: **Facts:**");
	}

	@Test
	void anAnswerWithoutCitationsFails() {
		assertThat(AnswerScorer.score(leaveCase(), "25 days.", List.of()).failures()).containsExactly("no citation");
	}

	@Test
	void unanswerableQuestionsMustBeDeclinedWithoutForbiddenClaims() {
		Case c = new Case("samira-leave", "attribution", null, "How much leave does Samira get?", null, null,
				new AnswerCheck("refusal", null, List.of("Samira gets 25"), null));

		assertThat(AnswerScorer.score(c, "The documents do not mention Samira's leave.", List.of()).passed()).isTrue();
		assertThat(AnswerScorer.score(c, "Samira gets 25 days [1].", HANDBOOK_CITED).failures()).containsExactly(
				"answered instead of saying the documents do not cover it",
				"contains forbidden text: Samira gets 25");
	}

	@Test
	void wordsAreCountedWithoutCitationMarkersAndMarkdown() {
		assertThat(AnswerScorer.wordCount("**Two** months [1][2]. - `code` [3, 4]")).isEqualTo(3);
	}

	@Test
	void theCommittedEvaluationSetIsValid() {
		EvaluationSet set = EvaluationSet.load(EvaluationSet.DEFAULT_LOCATION);

		assertThat(set.cases()).hasSizeGreaterThanOrEqualTo(30);
		// Every failure mode from manual testing is covered (PROJECT_SPEC §9, Phase 8).
		assertThat(set.cases()).extracting(Case::category)
			.contains("attribution", "padded", "false-not-found", "evaluative", "unanswerable", "injection");
	}

	private static Case leaveCase() {
		return new Case("leave", "fact", null, "How many days of leave?", null, HANDBOOK,
				new AnswerCheck(null, List.of(node("\"25\"")), List.of("**Facts:**"), 40));
	}

	private static JsonNode node(String json) {
		return JSON.readTree(json);
	}

}
