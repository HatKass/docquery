package dev.docquery.api.chat;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param model the configured chat model, recorded with every answer (when the provider's response
 * does not name it)
 * @param historyMessages earlier messages of the conversation sent along, so follow-up questions work;
 * 0 for none
 * @param maxQuestionLength longest accepted question, in characters
 * @param streamTimeout an answer stream is cut (and the answer marked failed) after this long
 */
@ConfigurationProperties("docquery.chat")
public record ChatProperties(String model, @DefaultValue("6") int historyMessages,
		@DefaultValue("2000") int maxQuestionLength, @DefaultValue("5m") Duration streamTimeout) {

	public ChatProperties {
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("docquery.chat.model must be configured");
		}
		if (historyMessages < 0 || maxQuestionLength < 1) {
			throw new IllegalArgumentException("Invalid docquery.chat limits");
		}
	}

}
