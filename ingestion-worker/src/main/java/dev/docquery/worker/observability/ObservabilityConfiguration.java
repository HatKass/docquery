package dev.docquery.worker.observability;

import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Traces, metrics and logs (ADR 0014); same setup as api-service. The worker's HTTP server only
 * serves actuator endpoints, so all its traces come from Kafka records.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

	/** Health probes and Prometheus scrapes are not traced. */
	@Bean
	ObservationPredicate ignoreActuatorRequests() {
		return (name, context) -> !(context instanceof ServerRequestObservationContext request)
				|| !request.getCarrier().getRequestURI().startsWith("/actuator");
	}

	/**
	 * Connects the {@code OTEL} appender of logback-spring.xml to the SDK, so log records are exported
	 * over OTLP with their trace and span id. Earlier records are buffered by the appender and sent now.
	 */
	@Bean
	InitializingBean openTelemetryLogAppenderInstaller(OpenTelemetry openTelemetry) {
		return () -> OpenTelemetryAppender.install(openTelemetry);
	}

}
