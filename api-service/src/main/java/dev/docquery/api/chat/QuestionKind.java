package dev.docquery.api.chat;

import java.util.regex.Pattern;

/**
 * Whether a question asks for an evaluation of a document (an assessment, opinion, summary,
 * comparison...) rather than a fact. Only such questions get the wider, document-focused context
 * when no document is named: for a factual question ("how many days of leave in year 3?") extra
 * passages only invite padded answers. English and French, matching the documents seen so far.
 */
final class QuestionKind {

	private static final Pattern EVALUATIVE = Pattern.compile(
			"\\b(assess|evaluat|opinion|think|review|summar|overview|recommend|compar|strength|weakness"
					+ "|suitab|good fit|fit for|good for|pros and cons"
					+ "|évalu|evalu|avis|pens(?:e|es|ez)\\b|résum|resum|synth|recommand|points? forts?|points? faibles?)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

	private QuestionKind() {
	}

	static boolean isEvaluative(String question) {
		return EVALUATIVE.matcher(question).find();
	}

}
