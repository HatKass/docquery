package dev.docquery.api.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * USD prices per million tokens, by model name. Costs are <em>estimates</em>: computed at report time
 * from recorded token counts and the prices configured now, so a price change applies to past usage
 * as well. Models without a configured price are reported as "unpriced", never silently as free.
 */
public final class PriceTable {

	/** USD per million input (prompt) and output (completion) tokens. */
	public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
	}

	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

	private final Map<String, Price> prices;

	private PriceTable(Map<String, Price> prices) {
		this.prices = Map.copyOf(prices);
	}

	/**
	 * Parses {@code "model=input/output;model=input/output"}, e.g.
	 * {@code "gpt-4.1-mini=0.40/1.60;text-embedding-3-small=0.02/0;llama3.2:3b=0/0"}. Model names may
	 * contain ':' and '.', so entries are separated by ';' and the price follows the last '='.
	 * @throws IllegalArgumentException on a malformed entry (fails application startup)
	 */
	public static PriceTable parse(String spec) {
		Map<String, Price> prices = new LinkedHashMap<>();
		if (spec != null) {
			for (String entry : spec.split(";")) {
				String trimmed = entry.strip();
				if (trimmed.isEmpty()) {
					continue;
				}
				int eq = trimmed.lastIndexOf('=');
				int slash = trimmed.indexOf('/', eq + 1);
				if (eq <= 0 || slash < 0) {
					throw new IllegalArgumentException(
							"Invalid model price '" + trimmed + "', expected model=input/output (USD per 1M tokens)");
				}
				try {
					BigDecimal input = new BigDecimal(trimmed.substring(eq + 1, slash).strip());
					BigDecimal output = new BigDecimal(trimmed.substring(slash + 1).strip());
					if (input.signum() < 0 || output.signum() < 0) {
						throw new NumberFormatException("negative");
					}
					prices.put(trimmed.substring(0, eq).strip(), new Price(input, output));
				}
				catch (NumberFormatException ex) {
					throw new IllegalArgumentException("Invalid model price '" + trimmed + "'", ex);
				}
			}
		}
		return new PriceTable(prices);
	}

	public Optional<Price> find(String model) {
		return Optional.ofNullable(this.prices.get(model));
	}

	/** Estimated USD cost, or empty if the model has no configured price. */
	public Optional<BigDecimal> cost(String model, long inputTokens, long outputTokens) {
		return find(model).map(price -> price.inputPerMillion()
			.multiply(BigDecimal.valueOf(inputTokens))
			.add(price.outputPerMillion().multiply(BigDecimal.valueOf(outputTokens)))
			.divide(MILLION, 6, RoundingMode.HALF_UP));
	}

}
