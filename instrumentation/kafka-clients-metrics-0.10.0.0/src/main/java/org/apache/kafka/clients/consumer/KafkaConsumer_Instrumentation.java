/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.apache.kafka.clients.consumer;

import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.Metrics;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.api.agent.DestinationType;
import com.newrelic.api.agent.MessageConsumeParameters;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.weaver.NewField;
import com.newrelic.api.agent.weaver.Weave;
import com.newrelic.api.agent.weaver.WeaveAllConstructors;
import com.newrelic.api.agent.weaver.Weaver;
import com.nr.instrumentation.kafka.NewRelicMetricsReporter;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.List;
import java.util.logging.Level;

@Weave(originalName = "org.apache.kafka.clients.consumer.KafkaConsumer")
public class KafkaConsumer_Instrumentation<K, V> {

    private final Metrics metrics = Weaver.callOriginal();

    @NewField
    private NewRelicMetricsReporter newRelicMetricsReporter;

    @NewField
    private boolean initialized;

    @WeaveAllConstructors
    public KafkaConsumer_Instrumentation() {
        if (!initialized) {
            metrics.addReporter(newRelicMetricsReporter);
            initialized = true;
        }
    }

    private ClusterResourceListeners configureClusterResourceListeners(Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer, List<?>... candidateLists) {
        // This must be instantiated here because default values for injected fields don't seem to get applied before the constructor,
        // and the constructor instrumentation happens after this method is called.
        newRelicMetricsReporter = new NewRelicMetricsReporter();

        AgentBridge.getAgent().getLogger().log(Level.FINEST, "configureClusterResourceListeners()");
        ClusterResourceListeners clusterResourceListeners = Weaver.callOriginal();
        clusterResourceListeners.maybeAdd(newRelicMetricsReporter);
        return clusterResourceListeners;
    }

    public ConsumerRecords<K, V> poll(long timeout) {
        final ConsumerRecords<K, V> records;
        try {
            records = Weaver.callOriginal();
        } catch (Exception e) {
            // Specifically ignore WakeupExceptions because they are common in non-error use cases
            if (!(e instanceof WakeupException)) {
                NewRelic.noticeError(e);
            }
            throw e;
        }

        for (ConsumerRecord record : records) {
            if (AgentBridge.getAgent().getTransaction(false) != null) {
                MessageConsumeParameters params = MessageConsumeParameters.library("Kafka")
                        .destinationType(DestinationType.NAMED_TOPIC)
                        .destinationName(record.topic())
                        .inboundHeaders(null)
                        .build();
                NewRelic.getAgent().getTransaction().getTracedMethod().reportAsExternal(params);
            }
            break;
        }
        return records;
    }

    public void close() {
        try {
            Weaver.callOriginal();
        } catch (Exception e) {
            NewRelic.noticeError(e); // Record an error when a consumer fails to close (most likely due to a timeout)
            throw e;
        }
    }

}
