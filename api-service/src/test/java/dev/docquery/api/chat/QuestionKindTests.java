package dev.docquery.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionKindTests {

	@ParameterizedTest
	@ValueSource(strings = { "What do you think about this CV for a confirmed junior post?",
			"Assess this CV for a junior role", "Summarize this document", "Give me an overview of the handbook",
			"Is this candidate a good fit for the team?", "Que penses-tu de ce CV ?", "Résume ce document",
			"Évalue ce profil pour un poste junior", "Quels sont les points forts du candidat ?" })
	void evaluativeQuestions(String question) {
		assertThat(QuestionKind.isEvaluative(question)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "How many days of paid leave in year 3?", "What are Hatim Kassim's main skills?",
			"When must expense claims be filed?", "How is my pension calculated?", "Combien de jours de congés ?" })
	void factualQuestions(String question) {
		assertThat(QuestionKind.isEvaluative(question)).isFalse();
	}

}
