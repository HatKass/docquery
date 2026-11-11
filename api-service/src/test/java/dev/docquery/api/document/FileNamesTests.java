package dev.docquery.api.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNamesTests {

	@Test
	void keepsOrdinaryNames() {
		assertThat(FileNames.sanitize("Employee Handbook 2026.pdf")).isEqualTo("Employee Handbook 2026.pdf");
		assertThat(FileNames.sanitize("Café menu – été.docx")).isEqualTo("Café menu – été.docx");
	}

	@Test
	void dropsDirectoryParts() {
		assertThat(FileNames.sanitize("C:\\fakepath\\report.pdf")).isEqualTo("report.pdf");
		assertThat(FileNames.sanitize("../../etc/passwd.txt")).isEqualTo("passwd.txt");
	}

	@Test
	void removesControlAndBidiCharacters() {
		assertThat(FileNames.sanitize("evil\u202Efdp.exe")).isEqualTo("evilfdp.exe");
		assertThat(FileNames.sanitize("line\nbreak\u0000.txt")).isEqualTo("linebreak.txt");
	}

	@Test
	void rejectsNamesWithNothingLeft() {
		assertThat(FileNames.sanitize(null)).isNull();
		assertThat(FileNames.sanitize("   ")).isNull();
		assertThat(FileNames.sanitize("folder/")).isNull();
		assertThat(FileNames.sanitize("..")).isNull();
	}

	@Test
	void truncatesLongNamesButKeepsTheExtension() {
		String name = FileNames.sanitize("a".repeat(400) + ".pdf");
		assertThat(name).hasSize(FileNames.MAX_LENGTH).endsWith("a.pdf");
	}

}
