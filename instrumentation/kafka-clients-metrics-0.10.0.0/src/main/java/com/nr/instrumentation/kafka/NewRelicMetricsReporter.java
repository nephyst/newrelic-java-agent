/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.nr.instrumentation.kafka;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.api.agent.NewRelic;
import org.apache.kafka.common.ClusterResource;
import org.apache.kafka.common.ClusterResourceListener;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.MetricsReporter;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class NewRelicMetricsReporter implements MetricsReporter, ClusterResourceListener {

    private static final boolean kafkaMetricsDebug = NewRelic.getAgent().getConfig().getValue("kafka.metrics.debug.enabled", false);

    private static final boolean metricsAsEvents = NewRelic.getAgent().getConfig().getValue("kafka.metrics.as_events.enabled", false);

    private static final long reportingIntervalInSeconds = NewRelic.getAgent().getConfig().getValue("kafka.metrics.interval", 30);

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, buildThreadFactory("NewRelicMetricsReporter-%d"));

    private final Map<String, KafkaMetric> clusterClientEvents = new ConcurrentHashMap<>();

    private final AtomicReference<String> clusterId = new AtomicReference<>(null);

    @Override
    public void init(final List<KafkaMetric> initMetrics) {
        for (KafkaMetric kafkaMetric : initMetrics) {
            String metricGroupAndName = getMetricGroupAndName(kafkaMetric);
            if (kafkaMetricsDebug) {
                AgentBridge.getAgent().getLogger().log(Level.FINEST, "init(): {0} = {1}", metricGroupAndName, kafkaMetric.metricName());
            }
            clusterClientEvents.put(metricGroupAndName, kafkaMetric);
            extractClientEvent(kafkaMetric);
        }

        final String metricPrefix = "MessageBroker/Kafka/Internal/";
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> eventData = new HashMap<>();
                    Set<Map<String, Object>> clusterClientEvents = new HashSet<>();
                    for (final Map.Entry<String, KafkaMetric> metric : NewRelicMetricsReporter.this.clusterClientEvents.entrySet()) {

                        Map<String, Object> clientEvent = extractClientEvent(metric.getValue());
                        if (clientEvent != null) {
                            clusterClientEvents.add(clientEvent);
                        }

                        final float value = Double.valueOf(metric.getValue().value()).floatValue();
                        if (kafkaMetricsDebug) {
                            AgentBridge.getAgent().getLogger().log(Level.FINEST, "getMetric: {0} = {1}", metric.getKey(), value);
                        }
                        if (!Float.isNaN(value) && !Float.isInfinite(value)) {
                            if (metricsAsEvents) {
                                eventData.put(metric.getKey().replace('/', '.'), value);
                            } else {
                                NewRelic.recordMetric(metricPrefix + metric.getKey(), value);
                            }
                        }
                    }
                    for (Map<String, Object> clientEvent : clusterClientEvents) {
                        if (kafkaMetricsDebug) {
                            AgentBridge.getAgent().getLogger().log(Level.FINEST, "KafkaClientMetrics {0}", clientEvent);
                        }
                        NewRelic.getAgent().getInsights().recordCustomEvent("KafkaClientMetrics", clientEvent);
                    }

                    if (metricsAsEvents) {
                        NewRelic.getAgent().getInsights().recordCustomEvent("KafkaMetrics", eventData);
                    }
                } catch (ConcurrentModificationException cme) {
                    // This is fixed in 1.0.3 and above but since this currently supports 0.11.0.0 we need to keep it here for now
                    // https://issues.apache.org/jira/browse/KAFKA-4950
                    // https://github.com/apache/kafka/pull/3907
                } catch (Exception e) {
                    AgentBridge.getAgent().getLogger().log(Level.FINE, e, "Unable to record kafka metrics");
                }
            }
        }, 0L, reportingIntervalInSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void metricChange(final KafkaMetric metric) {
        String metricGroupAndName = getMetricGroupAndName(metric);
        if (kafkaMetricsDebug) {
            AgentBridge.getAgent().getLogger().log(Level.FINEST, "metricChange(): {0} = {1}", metricGroupAndName, metric.metricName());
        }
        clusterClientEvents.put(metricGroupAndName, metric);
    }

    @Override
    public void metricRemoval(final KafkaMetric metric) {
        String metricGroupAndName = getMetricGroupAndName(metric);
        if (kafkaMetricsDebug) {
            AgentBridge.getAgent().getLogger().log(Level.FINEST, "metricRemoval(): {0} = {1}", metricGroupAndName, metric.metricName());
        }
        clusterClientEvents.remove(metricGroupAndName);
    }

    private String getMetricGroupAndName(final KafkaMetric metric) {
        if (metric.metricName().tags().containsKey("topic")) {
            // Special case for handling topic names in metrics
            return metric.metricName().group() + "/" + metric.metricName().tags().get("topic") + "/" + metric.metricName().name();
        }
        return metric.metricName().group() + "/" + metric.metricName().name();
    }

    private Map<String, Object> extractClientEvent(final KafkaMetric metric) {
        if (clusterId.get() == null) {
            return null;
        }

        Map<String, String> tags = metric.metricName().tags();
        String clientId = tags.get("client-id");
        String topic = tags.get("topic");
        String nodeId = tags.get("node-id");

        if (clientId == null || (topic == null && nodeId == null)) {
            return null;
        }

        String action = metric.metricName().group().split("-")[0];

        Map<String, Object> event = new HashMap<>();
        event.put("clusterId", clusterId);
        event.put("clientId", clientId);
        event.put("action", action);

        if (topic != null) {
            event.put("topic", topic);
        }
        if (nodeId != null) {
            event.put("nodeId", nodeId);
        }

        return event;
    }

    @Override
    public void onUpdate(ClusterResource clusterResource) {
        AgentBridge.getAgent().getLogger().log(Level.FINEST, "onUpdate(ClusterResource): {0}", clusterResource.clusterId());
        this.clusterId.set(clusterResource.clusterId());
    }

    @Override
    public void close() {
        executor.shutdown();
        clusterClientEvents.clear();
    }

    @Override
    public void configure(final Map<String, ?> configs) {
    }

    private static ThreadFactory buildThreadFactory(final String nameFormat) {
        // fail fast if the format is invalid
        String.format(nameFormat, 0);

        final ThreadFactory factory = Executors.defaultThreadFactory();
        final AtomicInteger count = new AtomicInteger();

        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                final Thread thread = factory.newThread(runnable);
                thread.setName(String.format(nameFormat, count.incrementAndGet()));
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
