package dev.docquery.api.support;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses a complete {@code text/event-stream} body into named JSON events (enough for our streams). */
public final class SseEvents {

	public record Event(String name, JsonNode data) {
	}

	private SseEvents() {
	}

	public static List<Event> parse(String body, JsonMapper json) {
		List<Event> events = new ArrayList<>();
		String name = "message";
		StringBuilder data = new StringBuilder();
		for (String line : (body + "\n\n").split("\r?\n", -1)) {
			if (line.isEmpty()) {
				if (!data.isEmpty()) {
					events.add(new Event(name, json.readTree(data.toString())));
				}
				name = "message";
				data.setLength(0);
			}
			else if (line.startsWith("event:")) {
				name = line.substring(6).strip();
			}
			else if (line.startsWith("data:")) {
				data.append(data.isEmpty() ? "" : "\n").append(line.substring(5));
			}
		}
		return events;
	}

	public static List<String> names(List<Event> events) {
		return events.stream().map(Event::name).toList();
	}

	public static Event last(List<Event> events) {
		return events.getLast();
	}

}
