/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ascentsream.tests.kop.common;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;

/**
 * Implement the same logic of kafka-consumer-groups.sh.
 */
public class ConsumerGroupsCli implements Closeable {

    private static final int TIMEOUT_MS = 5000;
    private final AdminClient admin;

    public ConsumerGroupsCli(AdminClient admin) {
        this.admin = admin;
    }

    @Override
    public void close() {
        admin.close();
    }

    public TreeMap<String, Pair<String, List<PartitionAssignmentState>>> collectGroupOffsets(List<String> groupIds) {
        final Map<String, ConsumerGroupDescription> consumerGroups = admin.describeConsumerGroups(groupIds,
                        new DescribeConsumerGroupsOptions().timeoutMs(TIMEOUT_MS)).describedGroups().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> wait(e.getValue())));
        final TreeMap<String, Pair<String, List<PartitionAssignmentState>>> groupOffsets = new TreeMap<>();
        consumerGroups.forEach((groupId, consumerGroup) -> {
            final ConsumerGroupState state = consumerGroup.state();
            final Map<TopicPartition, OffsetAndMetadata> committedOffsets = getCommittedOffsets(groupId);
            final ArrayList<TopicPartition> assignedTopicPartitions = new ArrayList<>();
            final Function<TopicPartition, Long> getPartitionOffset = topicPartition -> Optional.ofNullable(
                    committedOffsets.get(topicPartition)).map(OffsetAndMetadata::offset).orElse(null);
            final List<PartitionAssignmentState> rowsWithConsumer = consumerGroup.members().stream()
                    .filter(__ -> !__.assignment().topicPartitions().isEmpty())
                    .sorted(Comparator.comparingInt(__ -> __.assignment().topicPartitions().size()))
                    .flatMap(consumerSummary -> {
                        final Set<TopicPartition> topicPartitions = consumerSummary.assignment().topicPartitions();
                        assignedTopicPartitions.addAll(topicPartitions);
                        return collectConsumerAssignment(groupId, consumerGroup.coordinator(),
                                topicPartitions.stream().collect(Collectors.toList()), getPartitionOffset,
                                consumerSummary.consumerId(), consumerSummary.host(), consumerSummary.clientId());
                    })
                    .collect(Collectors.toList());
            final Map<TopicPartition, OffsetAndMetadata> unassignedPartitions = committedOffsets.entrySet().stream()
                    .filter(e -> !assignedTopicPartitions.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            final List<PartitionAssignmentState> rowsWithoutConsumer = unassignedPartitions.isEmpty()
                    ? Collections.emptyList() : collectConsumerAssignment(groupId, consumerGroup.coordinator(),
                    unassignedPartitions.keySet().stream().collect(Collectors.toList()), getPartitionOffset,
                    "-", "-", "-"
            ).collect(Collectors.toList());
            groupOffsets.put(groupId, Pair.of(state.toString(),
                    Stream.concat(rowsWithConsumer.stream(), rowsWithoutConsumer.stream())
                            .collect(Collectors.toList())));
        });
        return groupOffsets;
    }

    private static <T> T wait(KafkaFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> getCommittedOffsets(String groupId) {
        return wait(admin.listConsumerGroupOffsets(
                groupId,
                new ListConsumerGroupOffsetsOptions().timeoutMs(TIMEOUT_MS)
        ).partitionsToOffsetAndMetadata());
    }

    private Stream<PartitionAssignmentState> collectConsumerAssignment(
            String group, @Nullable Node coordinator, List<TopicPartition> topicPartitions,
            Function<TopicPartition, Long> getPartitionOffset, @Nullable String consumerId,
            @Nullable String host, @Nullable String clientId) {
        if (topicPartitions.isEmpty()) {
            return Stream.of(new PartitionAssignmentState(group, coordinator, null, null, null,
                    null, consumerId, host, clientId, null));
        } else {
            return describePartitions(group, coordinator, topicPartitions.stream()
                            .sorted(Comparator.comparingInt(TopicPartition::partition)).collect(Collectors.toList()),
                    getPartitionOffset, consumerId, host, clientId);
        }
    }

    private Stream<PartitionAssignmentState> describePartitions(
            String group, @Nullable Node coordinator, List<TopicPartition> topicPartitions,
            Function<TopicPartition, Long> getPartitionOffset, @Nullable String consumerId,
            @Nullable String host, @Nullable String clientId) {
        final BiFunction<TopicPartition, Long, PartitionAssignmentState> getDescribePartitionResult =
                (topicPartition, logEndOffset) -> {
                    final Long offset = getPartitionOffset.apply(topicPartition);
                    return new PartitionAssignmentState(group, coordinator, topicPartition.topic(),
                            topicPartition.partition(), offset, getLag(offset, logEndOffset), consumerId, host,
                            clientId, logEndOffset);
                };
        return getLogEndOffsets(topicPartitions).entrySet().stream().map(e -> {
            final Long logEndOffset = e.getValue();
            if (logEndOffset != null) {
                return getDescribePartitionResult.apply(e.getKey(), logEndOffset);
            } else {
                return getDescribePartitionResult.apply(e.getKey(), null);
            }
        });
    }

    private Map<TopicPartition, Long> getLogEndOffsets(List<TopicPartition> topicPartitions) {
        final Map<TopicPartition, OffsetSpec> endOffsets =
                KafkaClientUtils.listToMap(topicPartitions, __ -> OffsetSpec.latest());
        final Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets =
                wait(admin.listOffsets(endOffsets, new ListOffsetsOptions().timeoutMs(TIMEOUT_MS)).all());
        return KafkaClientUtils.listToMap(topicPartitions, topicPartition -> {
            final ListOffsetsResult.ListOffsetsResultInfo listOffsetsResultInfo = offsets.get(topicPartition);
            return listOffsetsResultInfo != null ? listOffsetsResultInfo.offset() : null;
        });
    }

    private @Nullable Long getLag(@Nullable Long offset, @Nullable Long logEndOffset) {
        if (offset == null || offset == -1 || logEndOffset == null) {
            return null;
        }
        return logEndOffset - offset;
    }

    public class PartitionAssignmentState {
        private final String group;
        private final Node coordinator;
        private final String topic;
        private final Integer partition;
        private final Long offset;
        private final Long lag;
        private final String consumerId;
        private final String host;
        private final String clientId;
        private final Long logEndOffset;

        public PartitionAssignmentState(String group, @Nullable Node coordinator, @Nullable String topic,
                                        @Nullable Integer partition, @Nullable Long offset, @Nullable Long lag,
                                        @Nullable String consumerId, @Nullable String host,
                                        @Nullable String clientId, @Nullable Long logEndOffset) {
            this.group = group;
            this.coordinator = coordinator;
            this.topic = topic;
            this.partition = partition;
            this.offset = offset;
            this.lag = lag;
            this.consumerId = consumerId;
            this.host = host;
            this.clientId = clientId;
            this.logEndOffset = logEndOffset;
        }

        public String group() {
            return this.group;
        }

        public Node coordinator() {
            return this.coordinator;
        }

        public String topic() {
            return this.topic;
        }

        public Integer partition() {
            return this.partition;
        }

        public Long offset() {
            return this.offset;
        }

        public Long lag() {
            return this.lag;
        }

        public String consumerId() {
            return this.consumerId;
        }

        public String host() {
            return this.host;
        }

        public String clientId() {
            return this.clientId;
        }

        public Long logEndOffset() {
            return this.logEndOffset;
        }
    }
}
