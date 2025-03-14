package com.ascentsream.tests.kop.task;

import com.ascentsream.tests.kop.common.DataUtil;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Data
public class ProducerTask {
    private static final Logger log = LoggerFactory.getLogger(ProducerTask.class);
    private KafkaProducer<String, Integer> producer;
    private final List<String> producedMessage;
    private final String topic;
    private final BlockingQueue<String> sendQueue;
    private final AtomicInteger sendCount;
    private final String producerOffsetFile;
    private volatile boolean isDone = false;

    public ProducerTask(KafkaProducer<String, Integer> producer, String topic,
                        List<String> producerMessages,
                        String producerOffsetFile,
                        AtomicInteger sendCount,
                        BlockingQueue<String> sendQueue) throws IOException {
        this.producedMessage = producerMessages;
        this.producerOffsetFile = producerOffsetFile;
        this.sendCount = sendCount;
        this.sendQueue = sendQueue;
        this.topic = topic;
        this.producer = producer;
    }

    public void start() {
        AtomicInteger sendFailedCount = new AtomicInteger();
        new Thread(new Runnable() {
            @Override
            public void run() {
                producedMessage.forEach(msg -> {
                    String[] objMsg = msg.split(",");
                    String key = null;
                    int value = -1;
                    if (objMsg.length > 1) {
                        key = objMsg[0];
                        value = Integer.valueOf(objMsg[1]);
                    } else {
                        value = Integer.valueOf(objMsg[0]);
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        log.error("InterruptedException ", e);
                    }
                    int finalValue = value;
                    producer.send(new ProducerRecord<String, Integer>(topic, key, finalValue), new Callback() {
                        @Override
                        public void onCompletion(RecordMetadata metadata, Exception exception) {
                            if (exception == null) {
                                sendCount.incrementAndGet();
                                sendQueue.add(metadata.partition() + "," + metadata.offset() + "," + finalValue + ","
                                        + topic);
                            } else {
                                sendFailedCount.incrementAndGet();
                                log.error("send error : ", exception);
                            }
                        }
                    });
                });
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while ((sendCount.get() + sendFailedCount.get()) < producedMessage.size() && !isDone) {
                    try {
                        log.info("sending msg : suc {}, failed {}, total {}.",
                                sendCount.get(), sendFailedCount.get(), producedMessage.size());
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        log.error("InterruptedException ", e);
                    }
                }
                isDone = true;
                log.info("sent msg : suc {}, failed {}, total {}.", sendCount.get(), sendFailedCount.get(),
                        producedMessage.size());
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
