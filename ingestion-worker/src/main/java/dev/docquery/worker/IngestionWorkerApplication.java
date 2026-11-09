package dev.docquery.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IngestionWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(IngestionWorkerApplication.class, args);
	}

}
