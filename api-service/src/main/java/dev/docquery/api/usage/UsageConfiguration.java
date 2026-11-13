package dev.docquery.api.usage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageProperties.class)
class UsageConfiguration {

	/** Parsed once at startup: a malformed USAGE_PRICES fails fast instead of at report time. */
	@Bean
	PriceTable priceTable(UsageProperties properties) {
		return PriceTable.parse(properties.prices());
	}

}
