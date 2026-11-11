package dev.docquery.api.document;

import static dev.docquery.api.document.DocumentStatus.CHUNKING;
import static dev.docquery.api.document.DocumentStatus.EMBEDDING;
import static dev.docquery.api.document.DocumentStatus.EXTRACTING;
import static dev.docquery.api.document.DocumentStatus.FAILED;
import static dev.docquery.api.document.DocumentStatus.READY;
import static dev.docquery.api.document.DocumentStatus.UPLOADED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentStatusTests {

	@Test
	void happyPathMovesForwardOneStepAtATime() {
		List<DocumentStatus> path = List.of(UPLOADED, EXTRACTING, CHUNKING, EMBEDDING, READY);
		for (int i = 0; i + 1 < path.size(); i++) {
			assertThat(path.get(i).canTransitionTo(path.get(i + 1))).as(path.get(i) + " -> " + path.get(i + 1)).isTrue();
		}
	}

	@ParameterizedTest
	@EnumSource(names = { "UPLOADED", "EXTRACTING", "CHUNKING", "EMBEDDING" })
	void everyInProgressStepCanFail(DocumentStatus status) {
		assertThat(status.canTransitionTo(FAILED)).isTrue();
		assertThat(status.isTerminal()).isFalse();
	}

	@Test
	void stepsNeverGoBackwardsOrSkipAhead() {
		assertThat(CHUNKING.canTransitionTo(EXTRACTING)).isFalse();
		assertThat(READY.canTransitionTo(EMBEDDING)).isFalse();
		assertThat(UPLOADED.canTransitionTo(READY)).isFalse();
		assertThat(EXTRACTING.canTransitionTo(EMBEDDING)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(DocumentStatus.class)
	void repeatingTheCurrentStatusIsNotATransition(DocumentStatus status) {
		assertThat(status.canTransitionTo(status)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(names = { "READY", "FAILED" })
	void terminalStatesCanOnlyRestart(DocumentStatus status) {
		assertThat(status.isTerminal()).isTrue();
		assertThat(status.canTransitionTo(UPLOADED)).isTrue();
		assertThat(List.of(DocumentStatus.values()).stream().filter(status::canTransitionTo)).containsExactly(UPLOADED);
	}

}
