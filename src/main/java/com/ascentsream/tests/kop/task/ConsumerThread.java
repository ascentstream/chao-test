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
package com.ascentsream.tests.kop.task;

import static com.ascentsream.tests.kop.common.KafkaClientUtils.checkPartitionLag;
import static com.ascentsream.tests.kop.common.KafkaClientUtils.getPartitionLag;
import java.time.Duration;
import java.util.Map;
import lombok.Data;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Data
public class ConsumerThread extends Thread {
    private static final Logger log = LoggerFactory.getLogger(ConsumerThread.class);
    private final KafkaConsumer<String, Integer> consumer;
    private final ConsumerTask consumerTask;
    private boolean running = false;

    public ConsumerThread(ConsumerTask consumerTask, KafkaConsumer<String, Integer> consumer) {
        this.consumer = consumer;
        this.consumerTask = consumerTask;
    }

    @Override
    public void run() {
        running = true;
        while (running) {
            try {
                ConsumerRecords<String, Integer> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, Integer> record : records) {
                    consumerTask.getConsumedCount().incrementAndGet();
                    consumerTask.getReceiveQueue().add(record.partition() + "," + record.offset() + "," + record.key()
                            + "," + record.value());
                }
                if (consumerTask.getConsumedCount().get() >= consumerTask.getMsgTotalNum()) {
                    Map<TopicPartition, Long> partitionLag = getPartitionLag(consumer, consumer.assignment());
                    if (!records.isEmpty()) {
                        partitionLag.forEach((topicPartition, lag) -> {
                            log.info("get group[{}] lag by consumer: topic {}, partition {}, lag {}",
                                    consumer.groupMetadata().groupId(), topicPartition.topic(),
                                    topicPartition.partition(), lag);
                        });
                    }

                    if (checkPartitionLag(partitionLag)) {
                        running = false;
                    }
                }
            } catch (Exception e) {
                log.error("consumer poll error,", e);
            }
        }
    }
}
