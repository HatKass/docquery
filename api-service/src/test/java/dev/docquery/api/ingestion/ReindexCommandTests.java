package dev.docquery.api.ingestion;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import dev.docquery.api.document.DocumentService;

/** The queueing itself is covered by the document API tests; this is the command's own guard. */
class ReindexCommandTests {

	@Test
	void refusesToRunWithoutAModelOrAll() {
		DocumentService documents = mock(DocumentService.class);
		OutboxRelay relay = mock(OutboxRelay.class);
		ReindexCommand command = new ReindexCommand(documents, relay, mock(ConfigurableApplicationContext.class), " ",
				false);

		// Without a target model, "re-index everything not embedded with <nothing>" would re-index all.
		assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments()))
			.withMessageContaining("--docquery.reindex.embedding-model");
		verifyNoInteractions(documents, relay);
	}

}
