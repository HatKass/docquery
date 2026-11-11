package dev.docquery.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkspaceRoleTests {

	@ParameterizedTest(name = "{0} includes {1}: {2}")
	@CsvSource({ "OWNER, OWNER, true", "OWNER, MEMBER, true", "OWNER, VIEWER, true", "MEMBER, OWNER, false",
			"MEMBER, MEMBER, true", "MEMBER, VIEWER, true", "VIEWER, OWNER, false", "VIEWER, MEMBER, false",
			"VIEWER, VIEWER, true" })
	void hierarchy(WorkspaceRole held, WorkspaceRole required, boolean allowed) {
		assertThat(held.includes(required)).isEqualTo(allowed);
	}

}
