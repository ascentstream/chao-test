package com.ascentsream.tests.kop.task;

import com.ascentsream.tests.kop.common.DataUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Data
public class ProducerTransactionTask {
    private static final Logger log = LoggerFactory.getLogger(ProducerTransactionTask.class);
    private KafkaProducer<String, Integer> producer;
    private final Map<String, List<String>> producerMessages;
    private final String[] topics;
    private final BlockingQueue<String> sendQueue;
    private final AtomicInteger sendCount;
    private final String producerOffsetFile;
    private volatile boolean isDone = false;

    public ProducerTransactionTask(KafkaProducer<String, Integer> producer, String[] topics,
                                   Map<String, List<String>> producerMessages,
                                   String producerOffsetFile,
                                   AtomicInteger sendCount,
                                   BlockingQueue<String> sendQueue) throws IOException {
        this.producerMessages = producerMessages;
        this.producerOffsetFile = producerOffsetFile;
        this.sendCount = sendCount;
        this.sendQueue = sendQueue;
        this.topics = topics;
        this.producer = producer;
    }

    public void start() {
        AtomicInteger sendFailedCount = new AtomicInteger();
        producer.initTransactions();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int oneTopicSendNum = producerMessages.get(topics[0]).size();
                for (int i = 0; i < oneTopicSendNum; i++) {
                    try {
                        producer.beginTransaction();
                        List<Future<RecordMetadata>> futures = new ArrayList<>();
                        Map<String , Integer> values = new HashMap<>();
                        for (String topic : topics) {
                            String msgStr = producerMessages.get(topic).get(i);
                            String[] objMsg = msgStr.split(",");
                            String key = null;
                            int value = -1;
                            if (objMsg.length > 1) {
                                key = objMsg[0];
                                value = Integer.valueOf(objMsg[1]);
                            } else {
                                value = Integer.valueOf(objMsg[0]);
                            }
                            int finalValue = value;
                            values.put(topic, finalValue);
                            Future<RecordMetadata> future =
                                    producer.send(new ProducerRecord<String, Integer>(topic, key, finalValue));
                            futures.add(future);
                        }
                        List<RecordMetadata> recordMetadatas = new ArrayList<>();
                        for (Future<RecordMetadata> future : futures) {
                            recordMetadatas.add(future.get(10, TimeUnit.SECONDS));
                        }
                        producer.commitTransaction();
                        recordMetadatas.forEach(metadata -> {
                            sendCount.incrementAndGet();
                            sendQueue.add(
                                    metadata.partition() + "," + metadata.offset() + ","
                                            + values.get(metadata.topic()) + "," + metadata.topic());
                        });
                    } catch (Exception e) {
                        try {
                            producer.abortTransaction();
                        } catch (Exception ex) {
                            log.error("ProducerFencedException, ", ex);
                        }
                        --i;
                        log.error("abort transaction : ", e);
                    }
                }
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int oneTopicSendNum = producerMessages.get(topics[0]).size();
                int sendAllNum = oneTopicSendNum * topics.length;
                while ((sendCount.get() + sendFailedCount.get()) < sendAllNum && !isDone) {
                    try {
                        log.info("sending msg : suc {}, failed {}, total {}.",
                                sendCount.get(), sendFailedCount.get(), sendAllNum);
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        log.error("InterruptedException ", e);
                    }
                }
                isDone = true;
                log.info("sent msg : suc {}, failed {}, total {}.", sendCount.get(), sendFailedCount.get(), sendAllNum);
                try {
                    DataUtil.writeQueueToFile(sendQueue, producerOffsetFile, "partition,offset,value,topic" );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                producer.close();
            }
        }).start();
    }
}