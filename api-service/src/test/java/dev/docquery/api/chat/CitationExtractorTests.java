package dev.docquery.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.docquery.api.chat.CitationExtractor.Extracted;

class CitationExtractorTests {

	@Test
	void collectsValidMarkersInAscendingOrder() {
		Extracted extracted = CitationExtractor.extract("Leave is 24 days [2]. Claims within 30 days [1, 3].", 3);

		assertThat(extracted.sourceNumbers()).containsExactly(1, 2, 3);
		assertThat(extracted.content()).isEqualTo("Leave is 24 days [2]. Claims within 30 days [1, 3].");
	}

	@Test
	void removesMarkersForSourcesThatWereNeverGiven() {
		Extracted extracted = CitationExtractor.extract("Fact one [1]. Invented fact [7]. Mixed [2, 9].", 2);

		assertThat(extracted.sourceNumbers()).containsExactly(1, 2);
		assertThat(extracted.content()).isEqualTo("Fact one [1]. Invented fact. Mixed [2].");
	}

	@Test
	void answerWithoutMarkersHasNoCitations() {
		Extracted extracted = CitationExtractor.extract("  Just an answer.  ", 4);

		assertThat(extracted.sourceNumbers()).isEmpty();
		assertThat(extracted.content()).isEqualTo("Just an answer.");
	}

	@ParameterizedTest
	@ValueSource(strings = { "I couldn't find this in your documents.", "I couldn’t find this in your documents [1].",
			"i couldn't find this in your documents", "I couldn't find this in your documents. Sorry about that!" })
	void notFoundVariantsBecomeTheExactSentenceWithoutCitations(String answer) {
		Extracted extracted = CitationExtractor.extract(answer, 3);

		assertThat(extracted.content()).isEqualTo(GroundedPrompt.NOT_FOUND_ANSWER);
		assertThat(extracted.sourceNumbers()).isEmpty();
	}

	@Test
	void aLongAnswerMerelyStartingLikeNotFoundIsKept() {
		String answer = "I couldn't find this in your documents directly, but the handbook says leave is 24 days "
				+ "per year for all staff, and more after five years of service [1].";

		assertThat(CitationExtractor.extract(answer, 1).sourceNumbers()).containsExactly(1);
	}

}
