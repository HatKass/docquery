package dev.docquery.api.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.evaluation.EvaluationResult.CaseResult;
import dev.docquery.api.evaluation.EvaluationResult.Summary;

/** Writes evaluation results as JSON (for tools and comparisons) and Markdown (for people). */
final class EvaluationReports {

	static final String COMPARISON_FILE = "README.md";

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private EvaluationReports() {
	}

	/** Writes {@code <name>.json} and {@code <name>.md} into the directory; returns the Markdown file. */
	static Path write(EvaluationResult result, Path directory, String name) {
		try {
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(name + ".json"), JSON.writeValueAsString(result).replace("\r\n", "\n") + "\n");
			Path markdown = directory.resolve(name + ".md");
			Files.writeString(markdown, markdown(result));
			return markdown;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static String markdown(EvaluationResult result) {
		Summary s = result.summary();
		StringBuilder md = new StringBuilder();
		md.append("# RAG evaluation: ").append(result.label()).append("\n\n");
		md.append("- Chat model: `").append(result.chatModel()).append("`\n");
		md.append("- Embedding model: `").append(result.embeddingModel()).append("`\n");
		md.append("- Run started: ").append(result.startedAt()).append("\n");
		md.append("- Cases: ").append(s.answers().cases()).append(" (evaluation/eval-set.json)\n\n");
		md.append("## Summary\n\n| Metric | Value |\n|---|---|\n");
		row(md, "Answers passing every check", percent(s.answers().passRate()));
		row(md, "Retrieval hit@1 / hit@5 (" + s.retrieval().cases() + " cases)",
				percent(s.retrieval().hitAt1()) + " / " + percent(s.retrieval().hitAt5()));
		row(md, "Retrieval MRR@10", String.format(Locale.ROOT, "%.3f", s.retrieval().mrr()));
		row(md, "Context recall (expected pages given to the model)", percent(s.retrieval().contextRecall()));
		row(md, "False \"not found\"", String.valueOf(s.answers().falseNotFound()));
		row(md, "Unanswerable questions declined", s.answers().refusalsCorrect());
		row(md, "Attribution cases passed", s.answers().attributionPassed());
		row(md, "Padded answers (over word limit)", String.valueOf(s.answers().padded()));
		row(md, "Citations correct (answerable cases)", s.answers().citationsCorrect());
		row(md, "Prompt injection resisted (no injected text in the answer)", s.answers().injectionResisted());
		row(md, "Failed requests", String.valueOf(s.answers().failedRequests()));
		row(md, "Answer latency p50 / p95 / max", s.latency().p50Millis() + " / " + s.latency().p95Millis() + " / "
				+ s.latency().maxMillis() + " ms");
		row(md, "Mean tokens (prompt / completion)",
				String.format(Locale.ROOT, "%.0f / %.0f", s.tokens().meanPrompt(), s.tokens().meanCompletion()));
		md.append("\n## By category\n\n| Category | Passed |\n|---|---|\n");
		s.answers().byCategory().forEach((category, passed) -> row(md, category, passed));
		md.append("\n## Cases (failures first)\n\n| Case | Category | Result | Hit rank | Context recall | Latency | Notes |\n|---|---|---|---|---|---|---|\n");
		for (CaseResult c : result.casesForReport()) {
			md.append("| ").append(c.id()).append(" | ").append(c.category()).append(" | ")
				.append(c.passed() ? "pass" : "**fail**").append(" | ")
				.append((c.firstHitRank() != null) ? c.firstHitRank() : (c.contextRecall() != null) ? "-" : "n/a")
				.append(" | ")
				.append((c.contextRecall() != null) ? percent(c.contextRecall()) : "n/a")
				.append(" | ").append(c.latencyMillis()).append(" ms | ")
				.append(cell(String.join("; ", c.failures()))).append(" |\n");
		}
		md.append("\n## Answers\n\n");
		for (CaseResult c : result.casesForReport()) {
			md.append("<details><summary>").append(c.passed() ? "pass" : "FAIL").append(" — ").append(c.id())
				.append(": ").append(escape(c.question())).append("</summary>\n\n");
			md.append("```text\n").append(c.answer().replace("```", "'''").strip()).append("\n```\n\n");
			md.append("Citations: ").append(c.citations().isEmpty() ? "none" : String.join("; ", c.citations()))
				.append("\n\n</details>\n\n");
		}
		return md.toString();
	}

	/**
	 * Rebuilds {@value #COMPARISON_FILE} in the directory from every result JSON in it: one row per
	 * run, so models can be compared at a glance.
	 */
	static Path writeComparison(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			List<EvaluationResult> results = new ArrayList<>();
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				results.add(JSON.readValue(Files.readString(file), EvaluationResult.class));
			}
			StringBuilder md = new StringBuilder("""
					# RAG evaluation results

					Generated by `LiveRagEvaluationTests` from the JSON files in this directory; do not edit by
					hand. How to run: [evaluation/README.md](../README.md). Each row is one run of the whole
					evaluation set with the same documents, retrieval settings and embedding model; only the chat
					model differs. Details per model: the `.md` file next to its `.json`.

					| Run | Chat model | Answers passed | False "not found" | Unanswerable declined | Attribution | Padded | Citations correct | Injection resisted | Retrieval hit@5 / MRR | Latency p50 / p95 |
					|---|---|---|---|---|---|---|---|---|---|---|
					""");
			for (EvaluationResult r : results) {
				Summary s = r.summary();
				md.append("| [").append(r.label()).append("](").append(fileName(r.label())).append(".md) | `")
					.append(r.chatModel()).append("` | ")
					.append(percent(s.answers().passRate())).append(" | ")
					.append(s.answers().falseNotFound()).append(" | ")
					.append(s.answers().refusalsCorrect()).append(" | ")
					.append(s.answers().attributionPassed()).append(" | ")
					.append(s.answers().padded()).append(" | ")
					.append(s.answers().citationsCorrect()).append(" | ")
					.append(s.answers().injectionResisted()).append(" | ")
					.append(percent(s.retrieval().hitAt5())).append(" / ")
					.append(String.format(Locale.ROOT, "%.2f", s.retrieval().mrr())).append(" | ")
					.append(seconds(s.latency().p50Millis())).append(" / ").append(seconds(s.latency().p95Millis()))
					.append(" |\n");
			}
			Path comparison = directory.resolve(COMPARISON_FILE);
			Files.writeString(comparison, md.toString());
			return comparison;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** A label as a file name: {@code qwen2.5:7b} becomes {@code qwen2.5-7b}. */
	static String fileName(String label) {
		return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "-").replaceAll("^-+|-+$", "");
	}

	private static void row(StringBuilder md, String metric, String value) {
		md.append("| ").append(metric).append(" | ").append(value).append(" |\n");
	}

	private static String percent(double share) {
		return String.format(Locale.ROOT, "%.0f %%", share * 100);
	}

	private static String seconds(long millis) {
		return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
	}

	private static String cell(String text) {
		return escape(text).replace("|", "\\|").replace("\n", " ");
	}

	private static String escape(String text) {
		return text.replace("<", "&lt;").replace(">", "&gt;");
	}

}
