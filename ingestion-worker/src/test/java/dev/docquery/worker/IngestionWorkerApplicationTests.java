package dev.docquery.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IngestionWorkerApplicationTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void healthEndpointReportsKafkaUp() {
		assertThat(this.mvc.get().uri("/actuator/health")).hasStatusOk().bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.status").isEqualTo("UP");
			json.assertThat().extractingPath("$.components.kafka.status").isEqualTo("UP");
			json.assertThat().extractingPath("$.components.kafka.details.nodes").isEqualTo(1);
		});
	}

	@Test
	void readinessProbeIncludesKafka() {
		assertThat(this.mvc.get().uri("/actuator/health/readiness")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("UP");
	}

}
