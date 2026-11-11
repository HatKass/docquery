package dev.docquery.api.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.TestcontainersConfiguration;

/**
 * Full application context against a real pgvector Postgres (Testcontainers). All subclasses share
 * one cached context and one database; tests isolate themselves by using fresh random users.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiIntegrationTest {

	@Autowired
	protected MockMvcTester mvc;

	@Autowired
	protected JsonMapper json;

	protected ApiClient api;

	@BeforeEach
	void setUpApiClient() {
		this.api = new ApiClient(this.mvc, this.json);
	}

}
