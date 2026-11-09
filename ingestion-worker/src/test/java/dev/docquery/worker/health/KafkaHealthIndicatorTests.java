package dev.docquery.worker.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class KafkaHealthIndicatorTests {

	private final Admin admin = mock(Admin.class);

	private final DescribeClusterResult result = mock(DescribeClusterResult.class);

	private final KafkaHealthIndicator indicator = new KafkaHealthIndicator(this.admin, Duration.ofMillis(200));

	@Test
	void upWithClusterDetailsWhenBrokerResponds() {
		given(this.admin.describeCluster(any(DescribeClusterOptions.class))).willReturn(this.result);
		given(this.result.clusterId()).willReturn(KafkaFuture.completedFuture("cluster-1"));
		given(this.result.nodes()).willReturn(KafkaFuture.completedFuture(List.of(new Node(1, "kafka", 9092))));

		Health health = this.indicator.health(true);

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("clusterId", "cluster-1").containsEntry("nodes", 1);
	}

	@Test
	void downWhenBrokerIsUnreachable() {
		KafkaFutureImpl<String> failed = new KafkaFutureImpl<>();
		failed.completeExceptionally(new TimeoutException("Timed out waiting for a node assignment"));
		given(this.admin.describeCluster(any(DescribeClusterOptions.class))).willReturn(this.result);
		given(this.result.clusterId()).willReturn(failed);

		Health health = this.indicator.health(true);

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
	}

}
