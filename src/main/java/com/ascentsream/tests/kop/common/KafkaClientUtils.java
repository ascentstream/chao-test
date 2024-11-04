/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ascentsream.tests.kop.common;

import com.ascentsream.tests.kop.AtLeastOnceMessaging;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KafkaClientUtils {
    private static final Logger log = LoggerFactory.getLogger(AtLeastOnceMessaging.class);

    private static KafkaConsumer<String, Integer> createKafkaConsumerUntilSuc(Properties props) {
        while (true) {
            try {
                return new KafkaConsumer<String, Integer>(props);
            } catch (Exception e) {
                log.error("new KafkaConsumer error, ", e );
            }
        }
    }

    private static KafkaProducer<String, Integer> createKafkaProducerUntilSuc(Properties props) {
        while (true) {
            try {
                return new KafkaProducer<String, Integer>(props);
            } catch (Exception e) {
                log.error("new KafkaConsumer error, ", e );
            }
        }
    }

    public static KafkaProducer<String, Integer> getKafkaProducer(final String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class);

        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 0);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000 * 60 * 10);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        props.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 10 * 60 * 1000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10 * 60 * 1000);
        return createKafkaProducerUntilSuc(props);
    }

    public static KafkaConsumer<String, Integer> getKafkaConsumer(final String bootstrapServers, String group) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000);
        return createKafkaConsumerUntilSuc(props);
    }

    public static AdminClient createKafkaAdmin(String bootstrapServers) {
        final Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        while (true) {
            try {
                return AdminClient.create(props);
            } catch (Exception e) {
                log.error("new adminClient error, ", e);
            }
        }
    }

    public static <K, V> Map<K, V> listToMap(final List<K> list,
                                             final Function<K, V> function) {
        return list.stream().collect(Collectors.toMap(key -> key, function));
    }

    /**
     * check whether the subscription group still has a backlog.
     *
     * @param topicPartitionLags .
     * @return boolean
     *
     */
    public static boolean checkPartitionLag(Map<TopicPartition, Long> topicPartitionLags) {
        int lag = 0;
        for (TopicPartition topicPartition : topicPartitionLags.keySet()) {
            lag += topicPartitionLags.get(topicPartition);
        }
        return lag == 0;
    }


    public static Map<TopicPartition, Long> getPartitionLag(KafkaConsumer consumer,
                                                            Set<TopicPartition> topicPartitions) {
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(topicPartitions);
        Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(topicPartitions);
        Map<TopicPartition, OffsetAndMetadata> commitoffsets =
                consumer.committed(topicPartitions);
        Map<TopicPartition, Long> lagMap = new ConcurrentHashMap<>();
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (entry.getValue().longValue() > 0 && commitoffsets.get(entry.getKey()) != null) {
                long endOffset = entry.getValue().longValue();
                long commitedOffset = commitoffsets.get(entry.getKey()).offset();
                lagMap.put(entry.getKey(), endOffset - commitedOffset);
            } else {
                long endOffset = entry.getValue().longValue();
                long beginOffset = beginningOffsets.getOrDefault(entry.getKey(), 0L);
                log.warn("{} has no commited offset, will regard begin offset as last committed offset, "
                        + "beginOffset {} endOffset {}", entry.getKey(), beginOffset, endOffset);
                lagMap.put(entry.getKey(), endOffset - beginOffset);
            }
        }
        return lagMap;
    }


    public static void printGroupLag(ConsumerGroupsCli consumerGroupsCli, String group, AtomicLong lagCount) {
        printGroupLag(consumerGroupsCli, group, lagCount, null, null);
    }


    public static void printGroupLag(ConsumerGroupsCli consumerGroupsCli, String group,
                                     AtomicLong lagCount, Map<Integer, Long> consumerLagOffsets,
                                     AtomicLong offsetSum) {
        TreeMap<String, Pair<String, List<ConsumerGroupsCli.PartitionAssignmentState>>> lags =
                consumerGroupsCli.collectGroupOffsets(Arrays.asList(group));
        log.info("get group[{}] status : {} ", group, lags.get(group).getKey());
        try {
            if (lags.get(group) != null && lags.get(group).getValue() != null) {
                lags.get(group).getValue().forEach(partitionAssignmentState -> {
                    long logEndOffset = partitionAssignmentState.logEndOffset();
                    if (partitionAssignmentState.lag() == null) {
                        return;
                    }
                    long lag = partitionAssignmentState.lag();
                    long offset = partitionAssignmentState.offset();
                    lagCount.addAndGet(lag);
                    if (offsetSum != null) {
                        offsetSum.addAndGet(offset);
                    }
                    int partition = partitionAssignmentState.partition();
                    if (consumerLagOffsets != null) {
                        consumerLagOffsets.put(partition, offset);
                    }
                    log.info("get group[{}] lag by admin: topic {}, partition {}, logEndOffset {}, offset {}, lag {}",
                            partitionAssignmentState.group(), partitionAssignmentState.topic(), partition, logEndOffset,
                            offset, lag);
                });
            }
        } catch (Exception e) {
            log.error("printGroupLag error, ", e);
        }
    }
}
