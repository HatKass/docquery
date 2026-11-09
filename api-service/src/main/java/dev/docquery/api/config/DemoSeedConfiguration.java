package dev.docquery.api.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds {@code classpath:db/demo} (demo users' memberships and the "Demo workspace") to Flyway's
 * locations when {@code docquery.demo.seed-enabled=true}. Production sets it to false, so the demo
 * seed never reaches a real database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "docquery.demo.seed-enabled")
class DemoSeedConfiguration {

	static final String DEMO_LOCATION = "classpath:db/demo";

	@Bean
	FlywayConfigurationCustomizer demoSeedLocation() {
		return configuration -> {
			List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
			locations.add(new Location(DEMO_LOCATION));
			configuration.locations(locations.toArray(Location[]::new));
		};
	}

}
