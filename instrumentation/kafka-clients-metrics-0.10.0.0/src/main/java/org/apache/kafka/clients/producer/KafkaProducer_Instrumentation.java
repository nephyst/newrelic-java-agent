/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.apache.kafka.clients.producer;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.api.agent.DestinationType;
import com.newrelic.api.agent.MessageProduceParameters;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import com.newrelic.api.agent.weaver.NewField;
import com.newrelic.api.agent.weaver.Weave;
import com.newrelic.api.agent.weaver.WeaveAllConstructors;
import com.newrelic.api.agent.weaver.Weaver;
import com.nr.instrumentation.kafka.CallbackWrapper;
import com.nr.instrumentation.kafka.NewRelicMetricsReporter;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Level;

@Weave(originalName = "org.apache.kafka.clients.producer.KafkaProducer")
public class KafkaProducer_Instrumentation<K, V> {

    private final Metrics metrics = Weaver.callOriginal();

    // We need separated instances of this metric reporter for each consumer/producer.
    // The same instance must be passed to the metrics object, and to the clusterResourceListeners.
    @NewField
    private NewRelicMetricsReporter newRelicMetricsReporter;

    @NewField
    private boolean initialized;

    @WeaveAllConstructors
    public KafkaProducer_Instrumentation() {
        if (!initialized) {
            metrics.addReporter(newRelicMetricsReporter);
            initialized = true;
        }
    }

    private ClusterResourceListeners configureClusterResourceListeners(Serializer<K> keySerializer, Serializer<V> valueSerializer, List<?>... candidateLists) {
        // This must be instantiated here because default values for injected fields don't seem to get applied before the constructor,
        // and the constructor instrumentation happens after this method is called.
        newRelicMetricsReporter = new NewRelicMetricsReporter();

        AgentBridge.getAgent().getLogger().log(Level.FINEST, "configureClusterResourceListeners()");
        ClusterResourceListeners clusterResourceListeners = Weaver.callOriginal();
        clusterResourceListeners.maybeAdd(newRelicMetricsReporter);
        return clusterResourceListeners;
    }

    @Trace
    private Future<RecordMetadata> doSend(ProducerRecord record, Callback callback) {
        if (callback != null) {
            // Wrap the callback so we can capture metrics about messages being produced
            callback = new CallbackWrapper(callback, record.topic());
        }
        if (AgentBridge.getAgent().getTransaction(false) != null) {
            // use null for headers so we don't try to do CAT
            MessageProduceParameters params = MessageProduceParameters.library("Kafka")
                    .destinationType(DestinationType.NAMED_TOPIC)
                    .destinationName(record.topic())
                    .outboundHeaders(null)
                    .build();
            NewRelic.getAgent().getTransaction().getTracedMethod().reportAsExternal(params);
        }

        try {
            return Weaver.callOriginal();
        } catch (Exception e) {
            Map<String, Object> atts = new HashMap<>();
            atts.put("topic_name", record.topic());
            NewRelic.noticeError(e, atts);
            throw e;
        }
    }
}
