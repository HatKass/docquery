package dev.docquery.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiServiceApplicationTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void flywayEnablesPgvectorExtension() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'",
				Integer.class);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void healthEndpointIsUpIncludingDatabase() {
		assertThat(mvc.get().uri("/actuator/health")).hasStatusOk()
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.status").isEqualTo("UP");
				json.assertThat().extractingPath("$.components.db.status").isEqualTo("UP");
			});
	}

	@Test
	void readinessProbeIsUp() {
		assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("UP");
	}

}
