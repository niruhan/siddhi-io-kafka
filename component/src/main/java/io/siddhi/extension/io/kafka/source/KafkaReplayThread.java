/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.extension.io.kafka.source;

import io.siddhi.core.stream.input.source.SourceEventListener;
import io.siddhi.extension.io.kafka.Constants;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.siddhi.extension.io.kafka.source.KafkaSource.ADAPTOR_ENABLE_AUTO_COMMIT;

/**
 * This runnable processes each Kafka message and sends it to siddhi.
 */
public class KafkaReplayThread implements Runnable {

    private static final Logger LOG = Logger.getLogger(KafkaReplayThread.class);
    private final KafkaConsumer<byte[], byte[]> consumer;
    // KafkaConsumer is not thread safe, hence we need a lock
    private final Lock consumerLock = new ReentrantLock();
    private final String partitions[];
    private SourceEventListener sourceEventListener;
    private String topics[];
    private volatile boolean paused;
    private volatile boolean inactive;
    private List<TopicPartition> partitionsList = new ArrayList<>();
    private String consumerThreadId;
    private boolean isPartitionWiseThreading = false;
    private boolean isBinaryMessage = false;
    private boolean enableOffsetCommit = false;
    private boolean enableAutoCommit = false;
    private boolean enableAsyncCommit;
    private boolean consumerClosed;
    private ReentrantLock lock;
    private Condition condition;
    private KafkaSource.KafkaSourceState kafkaSourceState;
    private String[] requiredProperties;
    private int trpLength;
    private int startOffset;
    private int endOffset;

    KafkaReplayThread(SourceEventListener sourceEventListener, String[] topics, String[] partitions,
                        Properties props, boolean isPartitionWiseThreading, boolean isBinaryMessage,
                        boolean enableOffsetCommit, boolean enableAsyncCommit, String[] requiredProperties,
                      int startOffset, int endOffset) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.consumer = new KafkaConsumer<>(props);
        this.sourceEventListener = sourceEventListener;
        this.topics = topics;
        this.partitions = partitions;
        this.isPartitionWiseThreading = isPartitionWiseThreading;
        this.isBinaryMessage = isBinaryMessage;
        this.enableOffsetCommit = false;
        this.enableAutoCommit = Boolean.parseBoolean(props.getProperty(ADAPTOR_ENABLE_AUTO_COMMIT, "true"));
        this.consumerThreadId = buildId();
        this.enableAsyncCommit = enableAsyncCommit;
        lock = new ReentrantLock();
        condition = lock.newCondition();
        this.requiredProperties = requiredProperties;
        if (requiredProperties != null && requiredProperties.length > 0) {
            trpLength = requiredProperties.length;
        } else {
            trpLength = 0;
        }
        if (null != partitions) {
            for (String topic : topics) {
                for (String partition1 : partitions) {
                    TopicPartition partition = new TopicPartition(topic, Integer.parseInt(partition1));
                    LOG.info("Adding partition " + partition1 + " for topic: " + topic);
                    partitionsList.add(partition);
                }
                LOG.info("Adding partitions " + Arrays.toString(partitions) + " for topic: " + topic);
                consumer.assign(partitionsList);
            }
        } else {
            consumer.subscribe(Arrays.asList(topics));
        }
        consumerClosed = false;
        LOG.info("Subscribed for topics: " + Arrays.toString(topics));
    }

    void pause() {
        paused = true;
    }

    void resume() {
//        restore();
//        paused = false;
//        try {
//            lock.lock();
//            condition.signalAll();
//        } finally {
//            lock.unlock();
//        }
    }
//
//    void restore() {
//        final Lock consumerLock = this.consumerLock;
//        if (kafkaSourceState != null && kafkaSourceState.getTopicOffsetMap() != null) {
//            for (String topic : topics) {
//                Map<Integer, Long> offsetMap = kafkaSourceState.getTopicOffsetMap().get(topic);
//                if (null != offsetMap) {
//                    for (Map.Entry<Integer, Long> entry : offsetMap.entrySet()) {
//                        TopicPartition partition = new TopicPartition(topic, entry.getKey());
//                        if (partitionsList.contains(partition)) {
//                            LOG.info("Seeking partition: " + partition + " for topic: " + topic + " offset: " + (entry
//                                    .getValue() + 1));
//                            try {
//                                consumerLock.lock();
//                                consumer.seek(partition, entry.getValue() + 1); //todo check this
//                            } finally {
//                                consumerLock.unlock();
//                            }
//                        }
//                    }
//                }
//            }
//        }
//    }

    @Override
    public void run() {
        final Lock consumerLock = this.consumerLock;
        while (!inactive) {
            if (paused) {
                lock.lock();
                try {
                    condition.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
            // The time, in milliseconds, spent waiting in poll if data is not available. If 0, returns
            // immediately with any records that are available now. Must not be negative
            ConsumerRecords<byte[], byte[]> records = null;
            try {
                consumerLock.lock();
                // TODO add a huge value because, when there are so many equal group ids, the group balancing
                // takes time and if this value is small, there will be an CommitFailedException while
                // trying to retrieve data
                consumer.seekToBeginning(partitionsList);
                records = consumer.poll(100); // todo check
            } catch (CommitFailedException ex) {
                LOG.warn("Consumer poll() failed." + ex.getMessage(), ex);
            } finally {
                consumerLock.unlock();
            }
            if (null != records) {
//                Map<SequenceKey, Integer> lastReceivedSeqNoMap = null;
//                if (kafkaSourceState.getConsumerLastReceivedSeqNoMap() != null) {
//                    lastReceivedSeqNoMap = kafkaSourceState.getConsumerLastReceivedSeqNoMap().get(consumerThreadId);
//                }
                for (ConsumerRecord record : records) {
                    String[] trpProperties = new String[trpLength];
                    if (!consumerClosed) {
                        if (record.offset() >= startOffset) {
                            if (record.offset() > endOffset) {
                                shutdownConsumer();
                                Thread.currentThread().interrupt();
                                break;
                            }
                            int partition = record.partition();
                            Object event = record.value();
                            Object eventBody = null;
                            String header = null;
                            long eventTimestamp = System.currentTimeMillis();
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Event received in Kafka Event Adaptor with offSet: " + record.offset() +
                                        ", key: " + record.key() + ", topic: " + record.topic() +
                                        ", partition: " + partition + ", recordTimestamp: " + record.timestamp() +
                                        ", eventTimestamp: " + eventTimestamp + ", checksum: " + record.checksum());
                            }
                            for (int i = 0; i < requiredProperties.length; i++) {
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_PARTITION)) {
                                    trpProperties[i] = String.valueOf(record.partition());
                                }
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_TOPIC)) {
                                    trpProperties[i] = record.topic();
                                }
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_KEY)) {
                                    trpProperties[i] = String.valueOf(record.key());
                                }
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_RECORD_TIMESTAMP)) {
                                    trpProperties[i] = String.valueOf(record.timestamp());
                                }
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_EVENT_TIMESTAMP)) {
                                    trpProperties[i] = String.valueOf(eventTimestamp);
                                }
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_CHECK_SUM)) {
                                    trpProperties[i] = String.valueOf(record.checksum());
                                }
                                if (requiredProperties[i].equalsIgnoreCase(Constants.TRP_OFFSET)) {
                                    trpProperties[i] = String.valueOf(record.offset());
                                    //todo check for end offset and break the loop
                                }
                            }
                            String transportSyncProperties = "topic:" + record.topic() + ",partition:"
                                    + record.partition() + ",offSet:" + record.offset();
                            String[] transportSyncPropertiesArr = new String[]{transportSyncProperties};
                            sourceEventListener.onEvent(event, trpProperties, transportSyncPropertiesArr);
                            if (record.offset() == endOffset) {
                                shutdownConsumer();
                                Thread.currentThread().interrupt();
                            }
//                        if (lastReceivedSeqNoMap == null) {
//                            sourceEventListener.onEvent(event, trpProperties, transportSyncPropertiesArr);
//                        } else {
//                            if (isBinaryMessage) {
//                                byte[] byteEvents = (byte[]) event;
//                                int stringSize = ByteBuffer.wrap(byteEvents).getInt();
//                                header = new String(byteEvents, 4, stringSize - 1, Charset.defaultCharset());
//                                eventBody = Arrays.copyOfRange(byteEvents, stringSize + 4,
//                                        byteEvents.length);
//                            } else {
//                                String stringEvent = event.toString();
//                                int headerStartingIndex = stringEvent.indexOf(KafkaSink.SEQ_NO_HEADER_DELIMITER);
//                                eventBody = stringEvent.substring(headerStartingIndex + 1);
//                                if (headerStartingIndex > 0) {
//                                    header = stringEvent.substring(0, headerStartingIndex);
//                                }
//                            }
//                            if (null != header && !header.isEmpty()) {
//                                String[] headerElements = header.split(KafkaSink.SEQ_NO_HEADER_FIELD_SEPERATOR);
//                                String sequenceId = headerElements[0];
//                                Integer seqNo = Integer.parseInt(headerElements[1]);
//                                SequenceKey sequenceKey = new SequenceKey(sequenceId, partition);
//                                Integer lastReceivedSeqNo = lastReceivedSeqNoMap.get(sequenceKey);
//                                if (lastReceivedSeqNo == null) {
//                                    lastReceivedSeqNo = -1;
//                                }
//                                if (lastReceivedSeqNo < seqNo) {
//                                    lastReceivedSeqNoMap.put(sequenceKey, seqNo);
//                                    sourceEventListener.onEvent(eventBody, trpProperties, transportSyncPropertiesArr);
//                                    if (LOG.isDebugEnabled()) {
//                                        LOG.debug("Last Received SeqNo Updated to:" + seqNo + " for " + "SeqKey:["
//                                                + sequenceKey.toString() + "] in Kafka consumer thread:"
//                                                + consumerThreadId);
//                                    }
//                                } else {
//                                    if (LOG.isDebugEnabled()) {
//                                        LOG.debug("Duplicate Message arrived at Kafka Consumer Thread:"
//                                                + consumerThreadId + ". SeqKey:[" + sequenceKey.toString() + "]"
//                                                + ", Latest SeqNo:" + lastReceivedSeqNo
//                                                + ", this message SeqNo:" + seqNo + ". Ignoring the message.");
//                                    }
//                                }
//
//                            } else {
//                                LOG.warn("'Sequenced' option is set to true in Kafka source configuration. "
//                                        + "But this message does not contain the sequence number in consumer thread :"
//                                        + consumerThreadId + ". Dropping the message");
//                            }
//                        }
//                        kafkaSourceState.getTopicOffsetMap().get(record.topic()).put(record.partition(),
//                                record.offset());
                        }
                    } else {
//                        kafkaSourceState.getTopicOffsetMap().get(record.topic()).put(record.partition(),
//                                record.offset());
                        break;
                    }
                }
//                if (enableOffsetCommit && !enableAutoCommit) {
//                    try {
//                        consumerLock.lock();
//                        if (!records.isEmpty()) {
//                            if (enableAsyncCommit) {
//                                consumer.commitAsync(new KafkaOffsetCommitCallback());
//                            } else {
//                                try {
//                                    consumer.commitSync();
//                                } catch (KafkaException e) {
//                                    LOG.error("Exception occurred when committing offsets Synchronously", e);
//                                }
//                            }
//                        }
//                    } catch (CommitFailedException e) {
//                        LOG.error("Kafka commit failed for topic kafka_result_topic", e);
//                    } finally {
//                        consumerLock.unlock();
//                    }
//                }
            }
            try { //To avoid thread spin
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void shutdownConsumer() {
        try {
            consumerLock.lock();
            consumer.close();
            consumerClosed = true;
        } finally {
            consumerLock.unlock();
        }
        inactive = true;
    }

    public String buildId() {
        StringBuilder key = new StringBuilder();
        int count = topics.length - 1;
        for (String topic : topics) {
            key.append(topic);
            if (--count >= 0) {
                key.append(":");
            }
        }


        if (partitions != null && isPartitionWiseThreading) {
            count = partitions.length - 1;
            key.append("-");
            for (String partition : partitions) {
                key.append(partition);
                if (--count >= 0) {
                    key.append(":");
                }
            }
        }
        return key.toString();
    }

//    public void setKafkaSourceState(KafkaSource.KafkaSourceState kafkaSourceState) {
//        this.kafkaSourceState = kafkaSourceState;
//        if (kafkaSourceState != null) {
//            if (kafkaSourceState.getConsumerLastReceivedSeqNoMap() != null) {
//                kafkaSourceState.getConsumerLastReceivedSeqNoMap().putIfAbsent(consumerThreadId, new HashMap<>());
//            }
//            for (String topic : topics) {
//                kafkaSourceState.getTopicOffsetMap().putIfAbsent(topic, new HashMap<>());
//            }
//        }
//    }

    private static class KafkaOffsetCommitCallback implements OffsetCommitCallback {
        @Override
        public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
            if (exception == null) {
                if (LOG.isDebugEnabled()) {
                    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
                        LOG.debug("Asynchronously commit offset done for " + entry.getKey().topic() +
                                " with offset of: " + entry.getValue().offset());
                    }
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
                        LOG.debug("Commit offset exception for " + entry.getKey().topic() +
                                " with offset of: " + entry.getValue().offset());
                    }
                }
                LOG.error("Exception occurred when committing offsets asynchronously.", exception);
            }
        }
    }
}

/**
 * This class represents the key which message sequences are tracked against. Any two consequent Messages having the
 * same sequence key must have increasing sequence numbers.
 * SequenceKey consists of the SequenceId which is a unique identifier for each kafka Sink and the kafka partition Id.
 */
//class SequenceKey {
//    private String sequenceId;
//    private int partitionId;
//
//    public SequenceKey(String sequenceId, int partitionId) {
//        this.sequenceId = sequenceId;
//        this.partitionId = partitionId;
//    }
//
//    @Override
//    public int hashCode() {
//        final int prime = 31;
//        int hash = 1;
//        hash = prime * hash + (sequenceId == null ? 0 : sequenceId.hashCode());
//        hash = prime * hash + partitionId;
//        return hash;
//    }
//
//    @Override
//    public boolean equals(Object object) {
//        if (object == this) {
//            return true;
//        }
//
//        if (!(object instanceof SequenceKey)) {
//            return false;
//        }
//
//        SequenceKey sequenceKey = (SequenceKey) object;
//
//        return ((sequenceKey.sequenceId.equals(this.sequenceId)) &&
//                (sequenceKey.partitionId == this.partitionId));
//    }
//
//    @Override
//    public String toString() {
//        return "SeqId:" + sequenceId + ", Partition" + ":" + partitionId;
//    }
//}
