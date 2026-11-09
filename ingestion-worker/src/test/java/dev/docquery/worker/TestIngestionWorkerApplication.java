package dev.docquery.worker;

import org.springframework.boot.SpringApplication;

public class TestIngestionWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.from(IngestionWorkerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
