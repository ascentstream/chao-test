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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataUtil {
    private static final Logger log = LoggerFactory.getLogger(DataUtil.class);

    /**
     * Create Test Data.
     *
     * @param fileName the file name where the test data is stored
     * @param keyNum   number of partitions
     * @param msgCount the total number of messages.
     *
     */
    public static void createTestData(String fileName, String keyPrefix, int keyNum, int msgCount) throws IOException {
        createTestData(fileName, keyPrefix, keyNum, msgCount, 0);
    }

    public static void createTestData(String fileName, String keyPrefix, int keyNum, int msgCount, int startValue)
            throws IOException {
        Path pathToFile = Paths.get(fileName);
        if (!Files.exists(pathToFile)) {
            Files.createDirectories(pathToFile.getParent());
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));
        writer.write(  "key,value");
        writer.newLine();
        for (int i = 0; i < msgCount; i++) {
            String key = "";
            if (keyNum <= 0) {
                throw new RuntimeException( "keyNum must > 0!");
            }
            key = keyPrefix + "_" + (i % (keyNum * 3));
            writer.write(key + "," + String.valueOf(startValue + i + 1));
            writer.newLine();
        }
        writer.flush();
        writer.close();
    }


    /**
     * Reading file contents as list .
     *
     * @param fileName the file name where the test data is stored
     * @return List<String>.
     *
     */
    public static List<String> readToListFromFile(String fileName, boolean removeTitle) throws IOException {
        List<String> datas = Files.readAllLines(Paths.get(fileName));
        if (removeTitle) {
            datas.remove(0);
        }
        return datas;
    }

    /**
     * Write the queue data to a file .
     *
     * @param queue blockingQueue, multithreading safety
     * @param fileName the file name where the test data is stored
     * @param title csv title
     *
     */
    public static void writeQueueToFile(BlockingQueue<String> queue, String fileName, String title) throws IOException {

        Path pathToFile = Paths.get(fileName);
        if (!Files.exists(pathToFile)) {
            Files.createDirectories(pathToFile.getParent());
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            writer.write(title);
            writer.newLine();
            while (!queue.isEmpty()) {
                String str = queue.poll();
                writer.write(str);
                writer.newLine();
            }
            log.info("write file {} success.", fileName);
        } catch (IOException e) {
            log.info("write file {} error.", fileName, e);
        }
    }

    /**
     * Check if data is lost.
     *
     * @param originMsgFile [key , value ]
     *      key_8,999
     *      key_9,1000
     * @param producerOffsetFile [partition, offset]
     *      0,1
     *      0,2
     * @param consumerMsgFile [partition, offset, key , value]
     *      0,1,key_8,999
     *      0,2,key_9,1000
     * @return boolean
     *
     */
    public static boolean checkDataNotLoss(String originMsgFile, String producerOffsetFile, String consumerMsgFile)
            throws IOException {

        List<String> originMessages = readToListFromFile(originMsgFile, true);
        List<String> producerOffset = readToListFromFile(producerOffsetFile, true);
        List<String> consumerMessages = readToListFromFile(consumerMsgFile, true);

        Set<Integer> producerMsgs = new HashSet<>();
        Set<Integer> consumerMsgs = new HashSet<>();
        //construct the offset of each partition from the consumption file
        Map<Integer, Long> receiveMaxOffsets = new TreeMap<>();
        //construct the offset of each partition from the production file
        Map<Integer, Long> sendMaxOffsets = new TreeMap<>();
        //calculate the total offset and the number of messages from the consumption file
        AtomicLong receiveAllOffset = new AtomicLong();
        //calculate the total offset and the number of messages from the production file
        AtomicLong sendAllOffset = new AtomicLong();
        //check whether the offset used for consumption is continuous
        Map<String, List<Long>> partitionOffsets = new HashMap<>();


        originMessages.forEach(str -> producerMsgs.add(Integer.valueOf(str.split(",")[1])));
        producerOffset.forEach(str ->
                sendMaxOffsets.put(Integer.valueOf(str.split(",")[0]), Long.valueOf(str.split(",")[1])));
        consumerMessages.forEach(str -> {
            String[] array = str.split(",");
            String partition = array[0];
            long offset = Long.parseLong(array[1]);
            int value = Integer.parseInt(array[3]);
            consumerMsgs.add(value);
            if (!partitionOffsets.containsKey(partition)) {
                partitionOffsets.put(partition, new ArrayList());
            }
            if (!partitionOffsets.get(partition).contains(offset)) {
                partitionOffsets.get(partition).add(offset);
                receiveMaxOffsets.put(Integer.valueOf(array[0]), offset);
            }
        });
        sendMaxOffsets.forEach((__, offfset) -> sendAllOffset.addAndGet(offfset + 1));
        receiveMaxOffsets.forEach((__, offfset) -> receiveAllOffset.addAndGet(offfset + 1));

        boolean ret = consumerMsgs.containsAll(producerMsgs);
        log.info("number of original distinct data : {}", producerMsgs.size());
        producerMsgs.removeAll(consumerMsgs);
        log.info("number of receive distinct data : {}", consumerMsgs.size());
        log.info("receive offset continuous check : {}", checkReceiveOffsetContinuous(partitionOffsets));
        log.info("number of loss data num: {}", producerMsgs.size());
        log.info("number of loss data : {}", producerMsgs);
        log.info("send max offsets : all {}, partition {}", sendAllOffset, sendMaxOffsets);
        log.info("receive max offsets : all {}, partition {}", receiveAllOffset, receiveMaxOffsets);
        return ret;
    }

    /**
     * Check if data is lost.
     *
     * @param producerMessages [key , value ]
     *      key_8,999
     *      key_9,1000
     * @param producerOffsetFile [partition, offset]
     *      0,1
     *      0,2
     * @param consumerMsgFile [partition, offset, key , value]
     *      0,1,key_8,999
     *      0,2,key_9,1000
     * @return boolean
     *
     */
    public static boolean checkExactlyConsumer(Map<String, List<String>> producerMessages, String producerOffsetFile,
                                               String consumerMsgFile) throws IOException {

        List<String> originMessages = new ArrayList<>();
        producerMessages.values().forEach(producerMessage -> originMessages.addAll(producerMessage));
        List<String> consumerMessages = readToListFromFile(consumerMsgFile, true);

        List<Integer> producerMsgs = new ArrayList<>();
        List<Integer> consumerMsgs = new ArrayList<>();


        originMessages.forEach(str -> producerMsgs.add(Integer.valueOf(str.split(",")[1])));
        consumerMessages.forEach(str -> {
            String[] array = str.split(",");
            int value = Integer.parseInt(array[3]);
            consumerMsgs.add(value);
        });

        boolean ret = producerMsgs.size() == originMessages.size() && consumerMsgs.containsAll(producerMsgs)
                && producerMsgs.containsAll(consumerMsgs);
        log.info("number of original data : {}", producerMsgs.size());
        producerMsgs.removeAll(consumerMsgs);
        log.info("number of receive data : {}", consumerMsgs.size());
        log.info("number of loss data num: {}", producerMsgs.size());
        log.info("number of loss data : {}", producerMsgs);
        return ret;
    }

    /**
     * check producer send offset increment .
     *
     * @param producerOffsetFile .
     * @return boolean
     *
     */
    public static boolean checkSendOffsetIncrement(String producerOffsetFile) throws IOException {
        List<String> producerOffsets = readToListFromFile(producerOffsetFile, true);
        Map<String, PriorityQueue<Integer>> offsets = new HashMap<>();
        for (String offsetStr : producerOffsets) {
            String[] arrays = offsetStr.split(",");
            String partition = arrays[0];
            int offset = Integer.valueOf(arrays[1]);
            String topic = arrays[3];
            String key = topic + "-" + partition;
            if (!offsets.containsKey(key)) {
                offsets.put(key, new PriorityQueue<>(Comparator.reverseOrder()));
                offsets.get(key).add(offset);
            } else {
                int max = offsets.get(key).peek();
                if (offset <= max) {
                    log.info("producerOffsetFile {} topic {} partition {} not increment, current {}, pre {}",
                            producerOffsetFile, topic, partition, offset, max);
                    return false;
                } else {
                    offsets.get(key).add(offset);
                }
            }
        }
        return true;
    }

    /**
     * check producer send offset increment .
     *
     * @param producerOffsetFile .
     * @return boolean
     *
     */
    public static boolean checkSendOffsetSequence(String producerOffsetFile) throws IOException {
        List<String> producerOffsets = readToListFromFile(producerOffsetFile, true);
        Map<String, PriorityQueue<Integer>> offsets = new HashMap<>();
        for (String offsetStr : producerOffsets) {
            String[] arrays = offsetStr.split(",");
            String partition = arrays[0];
            int offset = Integer.valueOf(arrays[1]);
            String topic = arrays[3];
            String key = topic + "-" + partition;
            if (!offsets.containsKey(key)) {
                offsets.put(key, new PriorityQueue<>(Comparator.reverseOrder()));
                offsets.get(key).add(offset);
            } else {
                int max = offsets.get(key).peek();
                if (offset != max + 1) {
                    log.info("producerOffsetFile {} topic {} partition {} not sequence, current {}, pre {}",
                            producerOffsetFile, topic, partition, offset, max);
                    return false;
                } else {
                    offsets.get(key).add(offset);
                }
            }
        }
        return true;
    }

    /**
     * check consumer receive offset increment .
     *
     * @param partitionOffsets .
     * @return boolean
     *
     */
    private static boolean checkReceiveOffsetContinuous(Map<String, List<Long>> partitionOffsets)  {
        boolean result = true;
        for (String partition : partitionOffsets.keySet()) {
            List<Long> offsets = partitionOffsets.get(partition);
            long prexOffset = -1;
            for (long offset : offsets) {
                if (offset != prexOffset + 1) {
                    log.info("consumer partition {} offset not continuous, current {}, pre {}, lost data {}", partition, offset,
                            prexOffset, (offset - prexOffset));
                    result =  false;
                }
                prexOffset = offset;
            }
        }
        return result;
    }

    public static int getTotalLines(String fileName) throws IOException {
        FileReader in = new FileReader(new File(fileName));
        LineNumberReader reader = new LineNumberReader(in);
        reader.skip(Long.MAX_VALUE);
        int lines = reader.getLineNumber()-1;
        reader.close();
        return lines;
    }
}
