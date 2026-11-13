package dev.docquery.api.chat;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.stereotype.Component;

/**
 * Chat answer telemetry. Each answer is one {@code docquery.chat.answer} observation: a span in the
 * question's trace (retrieval and model calls nest inside it) and a timer tagged with the outcome.
 * Spring AI adds the model-level {@code gen_ai.client.*} spans, timers and token counters itself.
 */
@Component
class ChatMetrics {

	/** The answer was streamed (model called). */
	static final String ANSWERED = "answered";

	/** Nothing relevant was found; the fixed "not found" answer was sent without a model call. */
	static final String NOT_FOUND = "not_found";

	/** Model unavailable, empty answer, timeout or the answer could not be stored. */
	static final String FAILED = "failed";

	/** The client went away while the answer streamed. */
	static final String CANCELLED = "cancelled";

	private final ObservationRegistry observations;

	private final Timer firstToken;

	ChatMetrics(ObservationRegistry observations, MeterRegistry meters) {
		this.observations = observations;
		this.firstToken = Timer.builder("docquery.chat.first.token")
			.description("Time from starting an answer to its first streamed text (retrieval + model latency)")
			.publishPercentileHistogram()
			.register(meters);
	}

	Observation startAnswer() {
		return Observation.createNotStarted("docquery.chat.answer", this.observations)
			.contextualName("chat answer")
			.start();
	}

	void recordFirstToken(Duration elapsed) {
		this.firstToken.record(elapsed);
	}

}
