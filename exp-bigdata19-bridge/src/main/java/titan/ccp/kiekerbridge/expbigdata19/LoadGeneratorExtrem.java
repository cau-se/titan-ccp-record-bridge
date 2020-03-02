package titan.ccp.kiekerbridge.expbigdata19;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.kafka.clients.producer.ProducerConfig;
import titan.ccp.configuration.events.Event;
import titan.ccp.kiekerbridge.KafkaRecordSender;
import titan.ccp.model.sensorregistry.MutableAggregatedSensor;
import titan.ccp.model.sensorregistry.MutableSensorRegistry;
import titan.ccp.models.records.ActivePowerRecord;

public class LoadGeneratorExtrem {

  public static void main(final String[] args) throws InterruptedException, IOException {

    final String hierarchy = Objects.requireNonNullElse(System.getenv("HIERARCHY"), "deep");
    final int numNestedGroups =
        Integer.parseInt(Objects.requireNonNullElse(System.getenv("NUM_NESTED_GROUPS"), "1"));
    final int numSensor =
        Integer.parseInt(Objects.requireNonNullElse(System.getenv("NUM_SENSORS"), "1"));
    final int value =
        Integer.parseInt(Objects.requireNonNullElse(System.getenv("VALUE"), "10"));
    final boolean sendRegistry =
        Boolean.parseBoolean(Objects.requireNonNullElse(System.getenv("SEND_REGISTRY"), "false"));
    final boolean doNothing =
        Boolean.parseBoolean(Objects.requireNonNullElse(System.getenv("DO_NOTHING"), "true"));
    final int threads =
        Integer.parseInt(Objects.requireNonNullElse(System.getenv("THREADS"), "1"));
    final String kafkaBootstrapServers =
        Objects.requireNonNullElse(System.getenv("KAFKA_BOOTSTRAP_SERVERS"), "localhost:9092");
    final String kafkaInputTopic =
        Objects.requireNonNullElse(System.getenv("KAFKA_INPUT_TOPIC"), "input");
    final String kafkaBatchSize = System.getenv("KAFKA_BATCH_SIZE");
    final String kafkaLingerMs = System.getenv("KAFKA_LINGER_MS");
    final String kafkaBufferMemory = System.getenv("KAFKA_BUFFER_MEMORY");

    final MutableSensorRegistry sensorRegistry = new MutableSensorRegistry("group_lvl_0");
    if (hierarchy.equals("deep")) {
      MutableAggregatedSensor lastSensor = sensorRegistry.getTopLevelSensor();
      for (int lvl = 1; lvl < numNestedGroups; lvl++) {
        lastSensor = lastSensor.addChildAggregatedSensor("group_lvl_" + lvl);
      }
      for (int s = 0; s < numSensor; s++) {
        lastSensor.addChildMachineSensor("sensor_" + s);
      }
    } else if (hierarchy.equals("full")) {
      addChildren(sensorRegistry.getTopLevelSensor(), numSensor, 1, numNestedGroups, 0);
    } else {
      throw new IllegalStateException();
    }

    final List<String> sensors =
        sensorRegistry.getMachineSensors().stream().map(s -> s.getIdentifier())
            .collect(Collectors.toList());

    if (sendRegistry) {
      final ConfigPublisher configPublisher =
          new ConfigPublisher(kafkaBootstrapServers, "configuration");
      configPublisher.publish(Event.SENSOR_REGISTRY_CHANGED, sensorRegistry.toJson());
      configPublisher.close();
      System.out.println("Configuration sent.");

      System.out.println("Now wait 30 seconds");
      Thread.sleep(30_000);
      System.out.println("And woke up again :)");
    }


    final Properties kafkaProperties = new Properties();
    // kafkaProperties.put("acks", this.acknowledges);
    kafkaProperties.compute(ProducerConfig.BATCH_SIZE_CONFIG, (k, v) -> kafkaBatchSize);
    kafkaProperties.compute(ProducerConfig.LINGER_MS_CONFIG, (k, v) -> kafkaLingerMs);
    kafkaProperties.compute(ProducerConfig.BUFFER_MEMORY_CONFIG, (k, v) -> kafkaBufferMemory);
    final KafkaRecordSender<ActivePowerRecord> kafkaRecordSender = new KafkaRecordSender<>(
        kafkaBootstrapServers, kafkaInputTopic, r -> r.getIdentifier(), r -> r.getTimestamp(),
        kafkaProperties);
    final KafkaRecordSender<ActivePowerRecord> kafkaRecordSender2 = new KafkaRecordSender<>(
        kafkaBootstrapServers, kafkaInputTopic, r -> r.getIdentifier(), r -> r.getTimestamp(),
        kafkaProperties);

    for (int i = 0; i < threads; i++) {
      final int threadId = i;
      new Thread(() -> {
        while (true) {
          for (final String sensor : sensors) {
            if (!doNothing) {
              if (threadId % 2 == 0) {
                kafkaRecordSender.write(new ActivePowerRecord(
                    sensor,
                    System.currentTimeMillis(),
                    value));
              } else {
                kafkaRecordSender2.write(new ActivePowerRecord(
                    sensor,
                    System.currentTimeMillis(),
                    value));
              }
            }
          }
        }
      }).start();
    }

    System.out.println("Wait for termination...");
    Thread.sleep(30 * 24 * 60 * 60 * 1000L);
    System.out.println("Will terminate now");
  }

  private static int addChildren(final MutableAggregatedSensor parent, final int numChildren,
      final int lvl, final int maxLvl, int nextId) {
    for (int c = 0; c < numChildren; c++) {
      if (lvl == maxLvl) {
        parent.addChildMachineSensor("s_" + nextId);
        nextId++;
      } else {
        final MutableAggregatedSensor newParent =
            parent.addChildAggregatedSensor("g_" + lvl + '_' + nextId);
        nextId++;
        nextId = addChildren(newParent, numChildren, lvl + 1, maxLvl, nextId);
      }
    }
    return nextId;
  }

}