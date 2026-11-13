package dev.docquery.api.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PriceTableTests {

	@Test
	void parsesModelNamesWithColonsAndDots() {
		PriceTable prices = PriceTable.parse(" llama3.2:3b=0/0 ; gpt-4.1-mini=0.40/1.60;;text-embedding-3-small=0.02/0 ");

		assertThat(prices.cost("llama3.2:3b", 1_000_000, 1_000_000)).contains(new BigDecimal("0.000000"));
		assertThat(prices.cost("gpt-4.1-mini", 1_000_000, 500_000)).contains(new BigDecimal("1.200000"));
		assertThat(prices.cost("text-embedding-3-small", 250_000, 0)).contains(new BigDecimal("0.005000"));
	}

	@Test
	void smallUsageIsNotRoundedAway() {
		PriceTable prices = PriceTable.parse("claude-haiku-5-5=0.10/0.50");

		// 1,200 prompt + 300 completion tokens: 0.00012 + 0.00015.
		assertThat(prices.cost("claude-haiku-5-5", 1200, 300)).contains(new BigDecimal("0.000270"));
	}

	@Test
	void unknownModelsHaveNoPriceRatherThanZero() {
		assertThat(PriceTable.parse("a=1/1").cost("b", 10, 10)).isEmpty();
		assertThat(PriceTable.parse("").cost("b", 10, 10)).isEmpty();
		assertThat(PriceTable.parse(null).cost("b", 10, 10)).isEmpty();
	}

	@Test
	void malformedEntriesFailFast() {
		assertThatIllegalArgumentException().isThrownBy(() -> PriceTable.parse("gpt-4.1-mini=0.40"));
		assertThatIllegalArgumentException().isThrownBy(() -> PriceTable.parse("gpt=abc/1"));
		assertThatIllegalArgumentException().isThrownBy(() -> PriceTable.parse("=1/1"));
		assertThatIllegalArgumentException().isThrownBy(() -> PriceTable.parse("gpt=-1/1"));
	}

}
