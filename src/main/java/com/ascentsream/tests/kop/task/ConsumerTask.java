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

import static com.ascentsream.tests.kop.common.KafkaClientUtils.getKafkaConsumer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import org.apache.kafka.clients.consumer.KafkaConsumer;

@Data
public class ConsumerTask {
    private final List<KafkaConsumer<String, Integer>> consumers = new ArrayList<>();
    private final List<ConsumerThread> consumerThreads = new ArrayList<>();
    // The total number of messages to be received.
    private final int msgTotalNum;
    // The received message will be written to the file later.
    private final BlockingQueue<String> receiveQueue;
    // Number of messages received.
    private final AtomicInteger consumedCount;

    public ConsumerTask(String bootstrapServers,
                        Set<String> topic,
                        String group,
                        int consumerNum,
                        int msgTotalNum,
                        AtomicInteger consumedCount,
                        BlockingQueue<String> receiveQueue,
                        boolean isTransaction) {
        this.msgTotalNum = msgTotalNum;
        this.consumedCount = consumedCount;
        this.receiveQueue = receiveQueue;
        for (int i = 0; i < consumerNum; i++) {
            KafkaConsumer<String, Integer> consumer = getKafkaConsumer(bootstrapServers, group, isTransaction);
            consumer.subscribe(topic);
            consumers.add(consumer);
        }
    }

    public void start() {
        receive();
    }

    public void close() throws InterruptedException {
        consumerThreads.forEach(consumerThread -> consumerThread.setRunning(false));
        Thread.sleep(2000);
        consumers.forEach(consumer -> consumer.close());
    }

    private void receive() {
        consumers.forEach(consumer -> {
            ConsumerThread consumerThread = new ConsumerThread(this, consumer);
            consumerThread.start();
            consumerThreads.add(consumerThread);
        });
    }
}
