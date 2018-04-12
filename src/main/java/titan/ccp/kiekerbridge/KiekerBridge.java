package titan.ccp.kiekerbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import kieker.common.record.IMonitoringRecord;
import teetime.framework.Execution;
import teetime.framework.OutputPort;
import titan.ccp.kiekerbridge.test.KafkaPowerConsumptionRecordSender;

public final class KiekerBridge {

	private final Execution<TerminatableConfiguration> execution;

	private final List<Runnable> onStartActions;

	private final List<Supplier<CompletableFuture<Void>>> onStopActions;

	private KiekerBridge(final TerminatableConfiguration configuration, final List<Runnable> onStartActions,
			final List<Supplier<CompletableFuture<Void>>> onStopActions) {
		this.execution = new Execution<>(configuration);
		this.onStartActions = onStartActions;
		this.onStopActions = onStopActions;

		Runtime.getRuntime().addShutdownHook(new Thread(this::stopBlocking));
	}

	public void start() {
		for (final Runnable onStartAction : this.onStartActions) {
			onStartAction.run();
		}
		this.execution.executeNonBlocking();
	}

	public CompletableFuture<Void> stop() {
		final CompletableFuture<Void> requestTerminationResult = this.execution.getConfiguration().requestTermination();
		final Stream<CompletableFuture<Void>> onStopActionResults = this.onStopActions.stream()
				.map(action -> action.get());
		final Stream<CompletableFuture<Void>> stopResults = Stream.concat(onStopActionResults,
				Stream.of(requestTerminationResult));

		return CompletableFuture.allOf(stopResults.toArray(size -> new CompletableFuture[size]));
	}

	public void stopBlocking() {
		this.stop().join();
	}

	public static Builder ofStream(final KiekerBridgeStream<? extends IMonitoringRecord> stream) {
		return new Builder(stream.getConfiguration(), stream.getLastOutputPort());
	}

	public static Builder ofConfiguration(final TerminatableConfiguration configuration,
			final OutputPort<? extends IMonitoringRecord> outputPort) {
		return new Builder(configuration, outputPort);
	}

	public static class Builder {

		private static final int TEETIME_DEFAULT_PIPE_CAPACITY = 512;

		private final Function<KafkaConfig, TerminatableConfiguration> configurationFactory;

		private final List<Runnable> onStartActions = new ArrayList<>(4);

		private final List<Supplier<CompletableFuture<Void>>> onStopActions = new ArrayList<>(4);

		private KafkaConfig kafkaConfig;

		private Builder(final TerminatableConfiguration configuration,
				final OutputPort<? extends IMonitoringRecord> outputPort) {

			this.configurationFactory = kafkaConfig -> {
				// final KafkaSenderStage senderStage = new KafkaSenderStage();
				final KafkaPowerConsumptionRecordSender kafkaSender = new KafkaPowerConsumptionRecordSender(
						kafkaConfig.bootstrapServers, kafkaConfig.topic, kafkaConfig.properties);
				final KafkaPowerConsumptionRecordSender.Stage senderStage = new KafkaPowerConsumptionRecordSender.Stage(
						kafkaSender);
				configuration.connectPorts(outputPort, senderStage.getInputPort(), TEETIME_DEFAULT_PIPE_CAPACITY);
				return configuration;
			};
		}

		public Builder onStart(final Runnable action) {
			this.onStartActions.add(action);
			return this;
		}

		public Builder onStop(final Supplier<CompletableFuture<Void>> action) {
			this.onStopActions.add(action);
			return this;
		}

		public Builder onStop(final Runnable action) {
			this.onStopActions.add(() -> {
				action.run();
				return CompletableFuture.completedFuture(null);
			});
			return this;
		}

		public Builder withKafkaConfiguration(final String bootstrapServers, final String topic,
				final Properties properties) {
			this.kafkaConfig = new KafkaConfig(bootstrapServers, topic, properties);
			return this;
		}

		public KiekerBridge build() {
			return new KiekerBridge(this.configurationFactory.apply(this.kafkaConfig), this.onStartActions,
					this.onStopActions);
		}

		private static class KafkaConfig {

			public final String bootstrapServers;
			public final String topic;
			public final Properties properties;

			public KafkaConfig(final String bootstrapServers, final String topic, final Properties properties) {
				this.bootstrapServers = bootstrapServers;
				this.topic = topic;
				this.properties = properties;
			}

		}

	}

}
