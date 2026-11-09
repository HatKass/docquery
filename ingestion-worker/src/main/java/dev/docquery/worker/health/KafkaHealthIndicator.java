package dev.docquery.worker.health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports the worker as DOWN when the Kafka cluster cannot be reached, so the
 * readiness probe fails and orchestrators stop treating the worker as ready.
 * Spring Boot does not ship a Kafka health indicator, hence this one.
 */
public class KafkaHealthIndicator extends AbstractHealthIndicator {

	private final Admin admin;

	private final Duration timeout;

	public KafkaHealthIndicator(Admin admin, Duration timeout) {
		super("Kafka health check failed");
		this.admin = admin;
		this.timeout = timeout;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) throws Exception {
		long timeoutMs = this.timeout.toMillis();
		DescribeClusterResult cluster = this.admin
			.describeCluster(new DescribeClusterOptions().timeoutMs((int) timeoutMs));
		String clusterId = cluster.clusterId().get(timeoutMs, TimeUnit.MILLISECONDS);
		int nodes = cluster.nodes().get(timeoutMs, TimeUnit.MILLISECONDS).size();
		builder.up().withDetail("clusterId", clusterId).withDetail("nodes", nodes);
	}

}
