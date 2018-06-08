package titan.ccp.kiekerbridge.test;

import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import com.google.common.base.Function;

import kieker.common.record.IMonitoringRecord;
import teetime.framework.AbstractConsumerStage;

public class KafkaRecordSender<T extends IMonitoringRecord> {

	private final String topic;

	private final Function<T, String> keyAccessor;

	private final Producer<String, T> producer;

	public KafkaRecordSender(final String bootstrapServers, final String topic) {
		this(bootstrapServers, topic, x -> "", new Properties());
	}

	public KafkaRecordSender(final String bootstrapServers, final String topic, final Function<T, String> keyAccessor) {
		this(bootstrapServers, topic, keyAccessor, new Properties());
	}

	public KafkaRecordSender(final String bootstrapServers, final String topic, final Function<T, String> keyAccessor,
			final Properties defaultProperties) {
		this.topic = topic;
		this.keyAccessor = keyAccessor;

		final Properties properties = new Properties();
		properties.putAll(defaultProperties);
		properties.put("bootstrap.servers", bootstrapServers);
		// properties.put("acks", this.acknowledges);
		// properties.put("batch.size", this.batchSize);
		// properties.put("linger.ms", this.lingerMs);
		// properties.put("buffer.memory", this.bufferMemory);

		this.producer = new KafkaProducer<>(properties, new StringSerializer(), null); // TODO
	}

	public void write(final T monitoringRecord) {
		final ProducerRecord<String, T> record = new ProducerRecord<>(this.topic,
				this.keyAccessor.apply(monitoringRecord), monitoringRecord);

		this.producer.send(record);
	}

	public void terminate() {
		this.producer.close();
	}

	public static class Stage<T extends IMonitoringRecord> extends AbstractConsumerStage<T> {

		private final KafkaRecordSender<T> sender;

		public Stage(final KafkaRecordSender<T> sender) {
			this.sender = sender;
		}

		@Override
		protected void execute(final T record) throws Exception {
			this.sender.write(record);
		}

		@Override
		protected void onTerminating() {
			this.sender.terminate();
			super.onTerminating();
		}

	}

}
