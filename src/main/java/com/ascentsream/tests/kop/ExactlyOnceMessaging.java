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
package com.ascentsream.tests.kop;

import static com.ascentsream.tests.kop.common.Constants.DATA_ROOT_PATH;
import static com.ascentsream.tests.kop.common.KafkaClientUtils.getKafkaProducer;
import com.ascentsream.tests.kop.common.ConsumerGroupsCli;
import com.ascentsream.tests.kop.common.DataUtil;
import com.ascentsream.tests.kop.common.KafkaClientUtils;
import com.ascentsream.tests.kop.common.PulsarClientUtils;
import com.ascentsream.tests.kop.task.ConsumerTask;
import com.ascentsream.tests.kop.task.ProducerTransactionTask;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExactlyOnceMessaging {
    private static final Logger log = LoggerFactory.getLogger(ExactlyOnceMessaging.class);

    public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis();
        String originMsgFilePrefix = DATA_ROOT_PATH + "/chao_test/origin/";
        String consumerMsgFile = DATA_ROOT_PATH+ "/chao_test/exactly-once/consumer/" + "revive-messaging.csv";
        String producerOffsetFile = DATA_ROOT_PATH + "/chao_test/exactly-once/producer/" + "offset.csv";
        int msgCount = Integer.parseInt(System.getProperty("send.msg.count", "1000"));
        int partitionNum = Integer.parseInt(System.getProperty("topic.partition", "10"));
        String bootstrapServers = System.getProperty("kafka.bootstrap.servers", "127.0.0.1:9092");
        String pulsarWebUrl = System.getProperty("pulsar.web.url", "http://pulsar-asp-proxy-headless:8080");
        String topicStr = System.getProperty("topic", "transaction-1,transaction-2");
        String group = System.getProperty("kafka.group.id", "group-1");
        long maxWaitingTime = Long.parseLong(System.getProperty("max.waiting.time", String.valueOf(10 * 60 )));

        Map<String, List<String>> producerMessages = new HashMap<>();
        AdminClient kafkaAdmin = KafkaClientUtils.createKafkaAdmin(bootstrapServers);
        String[] topics = topicStr.split(",");
        BlockingQueue<String> receiveQueue = new LinkedBlockingQueue<>(msgCount * topics.length * 2);
        BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(msgCount * topics.length * 2);
        PulsarAdmin pulsarAdmin = PulsarClientUtils.createPulsarAdmin(pulsarWebUrl);
        int startValue =0 ;
        for (int i = 0; i < topics.length; i++) {
            String topic = topics[i];
            if (kafkaAdmin.listTopics().names().get().contains(topic)) {
                kafkaAdmin.deleteTopics(Collections.singleton(topic)).all().get();
                Thread.sleep(3000);
            }
            NewTopic newTopic = new NewTopic(topic, partitionNum, (short) 2);
            Map<String, String> topicConfig = new HashMap<>();
            topicConfig.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(10 * 60 * 1000L));
            newTopic.configs(topicConfig);
            try {
                pulsarAdmin.topics().createPartitionedTopic(topic, partitionNum);
                pulsarAdmin.topicPolicies().setRetention(topic, new RetentionPolicies(-1, -1));
            } catch (Exception e) {
                log.error("createPartitionedTopic error : ", e);
            }
            String originMsgFile = originMsgFilePrefix + "messaging-key-" + i + ".csv";
            DataUtil.createTestData(originMsgFile, "transaction" + i, partitionNum, msgCount, startValue);
            producerMessages.put(topic, DataUtil.readToListFromFile(originMsgFile, true));
            startValue = msgCount;
        }
        int msgTotalNum = 0;
        for (String topic : topics ) {
            msgTotalNum += producerMessages.get(topic).size();
        }
        AtomicInteger sendCount = new AtomicInteger();
        AtomicInteger consumedCount = new AtomicInteger();
        ConsumerTask consumerTask = new ConsumerTask(bootstrapServers,
                new HashSet<>(Arrays.asList(topics)), group, partitionNum, msgTotalNum, consumedCount, receiveQueue,
                true);
        KafkaProducer<String, Integer> producer = getKafkaProducer(bootstrapServers, "id-" + System.currentTimeMillis());
        ProducerTransactionTask producerTask = new ProducerTransactionTask(producer, topics, producerMessages,
                producerOffsetFile, sendCount, sendQueue);
        Thread.sleep(5000);
        producerTask.start();
        consumerTask.start();
        Thread.sleep(5000);
        ConsumerGroupsCli consumerGroupsCli = new ConsumerGroupsCli(kafkaAdmin);
        while (true) {
            long waitingTime = (System.currentTimeMillis() - startTime);
            if (waitingTime > maxWaitingTime * 1000) {
                log.info("Waiting for {} exceed {} s, will exit!", waitingTime, maxWaitingTime);
                producerTask.setDone(true);
                Thread.sleep(10 * 1000);
                break;
            }
            log.info("group[{}] received msg count {} ", group, consumedCount.get());
            try {
                AtomicLong lagCount = new AtomicLong();
                KafkaClientUtils.printGroupLag(consumerGroupsCli, group, lagCount);
                if (lagCount.get() == 0L && consumedCount.get() >= msgTotalNum && producerTask.isDone()) {
                    Thread.sleep(10000);
                    break;
                }
            } catch (Exception e) {
                log.error( "printGroupLag error, ", e);
            }
            Thread.sleep(3000);
        }
        log.info("group[{}] received msg count {} ", group, consumedCount.get());

        DataUtil.writeQueueToFile(receiveQueue, consumerMsgFile, "partition,offset,key,value,topic");

        log.info("\n*************exactly once test end *************\n");
        log.info("*************results analysis*************");
        log.info("number of original messages : {}", msgTotalNum);
        log.info("number of successfully sent messages : {}", DataUtil.getTotalLines(producerOffsetFile));
        log.info("number of successful consumption : {}", DataUtil.getTotalLines(consumerMsgFile));

        boolean checkProduceSuc = DataUtil.checkSendOffsetIncrement(producerOffsetFile);
        log.info("check whether the sent offset is increasing : {}" , checkProduceSuc);
        boolean checkSuc = DataUtil.checkExactlyConsumer(producerMessages, producerOffsetFile, consumerMsgFile);
        log.info("chao test result : {}.", checkSuc);
        consumerTask.close();
        try {
            for (String topic : topics ) {
                PulsarClientUtils.printInternalStats(pulsarAdmin, topic);
                kafkaAdmin.deleteTopics(Collections.singleton(topic)).all();
            }
            kafkaAdmin.close();
            pulsarAdmin.close();
        } catch (Exception e) {
            log.error("clean resource error, ", e);
        }
    }
}
