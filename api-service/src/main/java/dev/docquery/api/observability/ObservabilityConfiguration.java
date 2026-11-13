package dev.docquery.api.observability;

import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Traces, metrics and logs (ADR 0014). Spring Boot does the heavy lifting (Micrometer Tracing on the
 * OpenTelemetry SDK, OTLP export, the Prometheus endpoint); this adds what is specific to DocQuery.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

	/**
	 * Keeps traces about user work. Not observed:
	 * <ul>
	 * <li>actuator requests (health probes, Prometheus scrapes every few seconds);</li>
	 * <li>scheduled-task runs: the outbox relay polls every 500 ms, and each relayed event gets its own
	 * span in the trace of the request that queued it instead (see {@code OutboxRelay}).</li>
	 * </ul>
	 */
	@Bean
	ObservationPredicate ignoreBackgroundNoise() {
		return (name, context) -> {
			if (context instanceof ServerRequestObservationContext request) {
				return !request.getCarrier().getRequestURI().startsWith("/actuator");
			}
			return !(context instanceof ScheduledTaskObservationContext);
		};
	}

	/**
	 * Applied by Spring Boot to the application task executor: work handed to it (the streamed chat
	 * answer: retrieval, model calls) stays in the trace of the request that started it.
	 */
	@Bean
	ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
		return new ContextPropagatingTaskDecorator();
	}

	/**
	 * Connects the {@code OTEL} appender of logback-spring.xml to the SDK, so log records are exported
	 * over OTLP with the trace and span id of the code that logged them. Records logged before this
	 * point are buffered by the appender and sent now.
	 */
	@Bean
	InitializingBean openTelemetryLogAppenderInstaller(OpenTelemetry openTelemetry) {
		return () -> OpenTelemetryAppender.install(openTelemetry);
	}

}
