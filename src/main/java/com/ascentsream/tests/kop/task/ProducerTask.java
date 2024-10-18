package com.ascentsream.tests.kop.task;

import static com.ascentsream.tests.kop.common.KafkaClientUtils.getKafkaProducer;
import com.ascentsream.tests.kop.common.DataUtil;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProducerTask {
    private static final Logger log = LoggerFactory.getLogger(ProducerTask.class);
    private KafkaProducer<String, Integer> producer;
    private final List<String> producedMessage;
    private final String topic;
    private final BlockingQueue<String> sendQueue;
    private final AtomicInteger sendCount;
    private final String producerOffsetFile;

    public ProducerTask(String bootstrapServers, String topic,
                        List<String> producerMessages,
                        String producerOffsetFile,
                        AtomicInteger sendCount,
                        BlockingQueue<String> sendQueue) throws IOException {
        this.producedMessage = producerMessages;
        this.producerOffsetFile = producerOffsetFile;
        this.sendCount = sendCount;
        this.sendQueue = sendQueue;
        this.topic = topic;
        this.producer = getKafkaProducer(bootstrapServers);
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
                    producer.send(new ProducerRecord<String, Integer>(topic, key, value), new Callback() {
                        @Override
                        public void onCompletion(RecordMetadata metadata, Exception exception) {
                            if (exception == null) {
                                sendCount.incrementAndGet();
                                sendQueue.add(metadata.partition() + "," + metadata.offset());
                            } else {
                                sendFailedCount.incrementAndGet();
                                exception.printStackTrace();
                            }
                        }
                    });
                });
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (sendCount.get() < producedMessage.size()) {
                    try {
                        log.info("sending msg : suc {}, failed {}, total {}.",
                                sendCount.get(), sendFailedCount.get(), producedMessage.size());
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                log.info("sent msg : suc {}, failed {}, total {}.", sendCount.get(), sendFailedCount.get(),
                        producedMessage.size());
                try {
                    DataUtil.writeQueueToFile(sendQueue, producerOffsetFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                producer.close();
            }
        }).start();
    }
}
