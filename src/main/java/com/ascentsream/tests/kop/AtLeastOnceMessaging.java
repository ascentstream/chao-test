package com.ascentsream.tests.kop;

import static com.ascentsream.tests.kop.common.Constants.DATA_ROOT_PATH;
import com.ascentsream.tests.kop.common.ConsumerGroupsCli;
import com.ascentsream.tests.kop.common.DataUtil;
import com.ascentsream.tests.kop.common.KafkaClientUtils;
import com.ascentsream.tests.kop.task.ConsumerTask;
import com.ascentsream.tests.kop.task.ProducerTask;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AtLeastOnceMessaging {
    private static final Logger log = LoggerFactory.getLogger(AtLeastOnceMessaging.class);

    public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis();
        String originMsgFile = DATA_ROOT_PATH + "/chao_test/origin/" + "messaging-key.txt";
        String consumerMsgFile = DATA_ROOT_PATH+ "/chao_test/at-least-once/consumer/" + "messaging-key.txt";
        String producerOffsetFile = DATA_ROOT_PATH + "/chao_test/at-least-once/producer/" + "offset.txt";
        int msgCount = Integer.parseInt(System.getProperty("send.msg.count", "1000"));
        int partitionNum = Integer.parseInt(System.getProperty("topic.partition", "10"));
        String bootstrapServers = System.getProperty("kafka.bootstrap.servers", "127.0.0.1:9092");
        String topic = System.getProperty("topic", "at-least-once");
        String group = System.getProperty("kafka.group.id", "group-1");
        long maxWaitingTime = Long.parseLong(System.getProperty("max.waiting.time", String.valueOf(10 * 60 )));

        BlockingQueue<String> receiveQueue = new LinkedBlockingQueue<>(msgCount * 2);
        BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(msgCount * 2);
        DataUtil.createTestData(originMsgFile, partitionNum, msgCount);
        List<String> producerMessages = DataUtil.readToListFromFile(originMsgFile);
        AdminClient kafkaAdmin = KafkaClientUtils.createKafkaAdmin(bootstrapServers);

        if (kafkaAdmin.listTopics().names().get().contains(topic)) {
            kafkaAdmin.deleteTopics(Collections.singleton(topic)).all().get();
            Thread.sleep(3000);
        }

        kafkaAdmin.createTopics(
                Collections.singleton(new NewTopic(topic, partitionNum, (short) 2))).all().get();
        AtomicInteger sendCount = new AtomicInteger();
        AtomicInteger consumedCount = new AtomicInteger();
        ConsumerTask consumerTask = new ConsumerTask(bootstrapServers, topic, group, partitionNum,
                producerMessages.size(), consumedCount, receiveQueue);
        ProducerTask producerTask = new ProducerTask(bootstrapServers, topic, producerMessages, producerOffsetFile,
                sendCount, sendQueue);
        producerTask.start();
        consumerTask.start();
        Thread.sleep(3000);

        ConsumerGroupsCli consumerGroupsCli = new ConsumerGroupsCli(kafkaAdmin);
        while (true) {
            log.info("group[{}] received msg count {} ", group, consumedCount.get());
            try {
                TreeMap<String, Pair<String, List<ConsumerGroupsCli.PartitionAssignmentState>>> lags =
                        consumerGroupsCli.collectGroupOffsets(Arrays.asList(group));
                AtomicLong lagCount = new AtomicLong();
                KafkaClientUtils.printGroupLag(consumerGroupsCli, group, lagCount);
                if (lagCount.get() <= 0L && consumedCount.get() >= producerMessages.size() && producerTask.isDone()) {
                    Thread.sleep(10000);
                    break;
                }
                long waitingTime = (System.currentTimeMillis() - startTime);
                if (waitingTime > maxWaitingTime * 1000) {
                    log.info("Waiting for {} exceed {} s, will exit!", waitingTime, maxWaitingTime);
                    producerTask.setDone(true);
                    Thread.sleep(10000);
                    break;
                }
            } catch (Exception e) {
                log.error( "printGroupLag error, ", e);
            }
            Thread.sleep(3000);
        }
        log.info("group[{}] received msg count {} ", group, consumedCount.get());

        DataUtil.writeQueueToFile(receiveQueue, consumerMsgFile);
        log.info("\n*************At least once test end *************\n");
        log.info("*************results analysis*************");
        log.info("number of original messages : {}", DataUtil.getTotalLines(originMsgFile));
        log.info("number of successfully sent messages : {}", DataUtil.getTotalLines(producerOffsetFile));
        log.info("number of successful consumption : {}", DataUtil.getTotalLines(consumerMsgFile));

        boolean checkProduceSuc = DataUtil.checkSendOffsetIncrement(producerOffsetFile);
        log.info("check whether the sent offset is increasing : {}" , checkProduceSuc);

        boolean checkSuc = DataUtil.checkDataNotLoss(originMsgFile, producerOffsetFile, consumerMsgFile);
        Map<Integer, Long> consumerLagOffsets = new TreeMap<>();
        AtomicLong offsetCount = new AtomicLong();
        AtomicLong lagCount = new AtomicLong();
        KafkaClientUtils.printGroupLag(consumerGroupsCli, group, lagCount, consumerLagOffsets, offsetCount);
        log.info("offsets by admin : all offset {} , partitions {}, lag {}", offsetCount.get(), consumerLagOffsets,
                lagCount);
        log.info("at least one scenario test result : {}.", checkSuc);
        consumerTask.close();
        kafkaAdmin.deleteTopics(Collections.singleton(topic)).all().get();
        kafkaAdmin.close();
    }
}
