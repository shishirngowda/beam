/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.bigquery;

import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants;
import org.apache.beam.runners.core.metrics.MonitoringInfoMetricName;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.metrics.MetricsContainer;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.metrics.MetricsLogger;
import org.apache.beam.sdk.metrics.SinkMetrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.SystemDoFnInternal;
import org.apache.beam.sdk.values.FailsafeValueInSingleWindow;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.ShardedKey;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.ValueInSingleWindow;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableSet;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Lists;
import org.joda.time.Instant;

/** Implementation of DoFn to perform streaming BigQuery write. */
@SystemDoFnInternal
@VisibleForTesting
@SuppressWarnings({
  "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
})
class StreamingWriteFn<ErrorT, ElementT>
    extends DoFn<KV<ShardedKey<String>, TableRowInfo<ElementT>>, Void> {
  private final BigQueryServices bqServices;
  private final InsertRetryPolicy retryPolicy;
  private final TupleTag<ErrorT> failedOutputTag;
  private final ErrorContainer<ErrorT> errorContainer;
  private final boolean skipInvalidRows;
  private final boolean ignoreUnknownValues;
  private final boolean ignoreInsertIds;
  private final SerializableFunction<ElementT, TableRow> toTableRow;
  private final SerializableFunction<ElementT, TableRow> toFailsafeTableRow;
  private final Set<MetricName> metricFilter;

  /** JsonTableRows to accumulate BigQuery rows in order to batch writes. */
  private transient Map<String, List<FailsafeValueInSingleWindow<TableRow, TableRow>>> tableRows;

  /** The list of unique ids for each BigQuery table row. */
  private transient Map<String, List<String>> uniqueIdsForTableRows;

  /** Tracks bytes written, exposed as "ByteCount" Counter. */
  private Counter byteCounter = SinkMetrics.bytesWritten();

  StreamingWriteFn(
      BigQueryServices bqServices,
      InsertRetryPolicy retryPolicy,
      TupleTag<ErrorT> failedOutputTag,
      ErrorContainer<ErrorT> errorContainer,
      boolean skipInvalidRows,
      boolean ignoreUnknownValues,
      boolean ignoreInsertIds,
      SerializableFunction<ElementT, TableRow> toTableRow,
      SerializableFunction<ElementT, TableRow> toFailsafeTableRow) {
    this.bqServices = bqServices;
    this.retryPolicy = retryPolicy;
    this.failedOutputTag = failedOutputTag;
    this.errorContainer = errorContainer;
    this.skipInvalidRows = skipInvalidRows;
    this.ignoreUnknownValues = ignoreUnknownValues;
    this.ignoreInsertIds = ignoreInsertIds;
    this.toTableRow = toTableRow;
    this.toFailsafeTableRow = toFailsafeTableRow;
    ImmutableSet.Builder<MetricName> setBuilder = ImmutableSet.builder();
    setBuilder.add(
        MonitoringInfoMetricName.named(
            MonitoringInfoConstants.Urns.API_REQUEST_LATENCIES,
            BigQueryServicesImpl.API_METRIC_LABEL));
    for (String status : BigQueryServicesImpl.DatasetServiceImpl.CANONICAL_STATUS_MAP.values()) {
      setBuilder.add(
          MonitoringInfoMetricName.named(
              MonitoringInfoConstants.Urns.API_REQUEST_COUNT,
              ImmutableMap.<String, String>builder()
                  .putAll(BigQueryServicesImpl.API_METRIC_LABEL)
                  .put(MonitoringInfoConstants.Labels.STATUS, status)
                  .build()));
    }
    setBuilder.add(
        MonitoringInfoMetricName.named(
            MonitoringInfoConstants.Urns.API_REQUEST_COUNT,
            ImmutableMap.<String, String>builder()
                .putAll(BigQueryServicesImpl.API_METRIC_LABEL)
                .put(
                    MonitoringInfoConstants.Labels.STATUS,
                    BigQueryServicesImpl.DatasetServiceImpl.CANONICAL_STATUS_UNKNOWN)
                .build()));
    this.metricFilter = setBuilder.build();
  }

  /** Prepares a target BigQuery table. */
  @StartBundle
  public void startBundle() {
    tableRows = new HashMap<>();
    uniqueIdsForTableRows = new HashMap<>();
  }

  /** Accumulates the input into JsonTableRows and uniqueIdsForTableRows. */
  @ProcessElement
  public void processElement(
      @Element KV<ShardedKey<String>, TableRowInfo<ElementT>> element,
      @Timestamp Instant timestamp,
      BoundedWindow window,
      PaneInfo pane) {
    String tableSpec = element.getKey().getKey();
    List<FailsafeValueInSingleWindow<TableRow, TableRow>> rows =
        BigQueryHelpers.getOrCreateMapListValue(tableRows, tableSpec);
    List<String> uniqueIds =
        BigQueryHelpers.getOrCreateMapListValue(uniqueIdsForTableRows, tableSpec);

    TableRow tableRow = toTableRow.apply(element.getValue().tableRow);
    TableRow failsafeTableRow = toFailsafeTableRow.apply(element.getValue().tableRow);
    rows.add(FailsafeValueInSingleWindow.of(tableRow, timestamp, window, pane, failsafeTableRow));
    uniqueIds.add(element.getValue().uniqueId);
  }

  /** Writes the accumulated rows into BigQuery with streaming API. */
  @FinishBundle
  public void finishBundle(FinishBundleContext context) throws Exception {
    List<ValueInSingleWindow<ErrorT>> failedInserts = Lists.newArrayList();
    BigQueryOptions options = context.getPipelineOptions().as(BigQueryOptions.class);
    for (Map.Entry<String, List<FailsafeValueInSingleWindow<TableRow, TableRow>>> entry :
        tableRows.entrySet()) {
      TableReference tableReference = BigQueryHelpers.parseTableSpec(entry.getKey());
      flushRows(
          tableReference,
          entry.getValue(),
          uniqueIdsForTableRows.get(entry.getKey()),
          options,
          failedInserts);
    }
    tableRows.clear();
    uniqueIdsForTableRows.clear();

    for (ValueInSingleWindow<ErrorT> row : failedInserts) {
      context.output(failedOutputTag, row.getValue(), row.getTimestamp(), row.getWindow());
    }
    reportStreamingApiLogging(options);
  }

  private void reportStreamingApiLogging(BigQueryOptions options) {
    MetricsContainer processWideContainer = MetricsEnvironment.getProcessWideContainer();
    if (processWideContainer instanceof MetricsLogger) {
      MetricsLogger processWideMetricsLogger = (MetricsLogger) processWideContainer;
      processWideMetricsLogger.tryLoggingMetrics(
          "BigQuery HTTP API Metrics: \n",
          metricFilter,
          options.getBqStreamingApiLoggingFrequencySec() * 1000L,
          true);
    }
  }

  /** Writes the accumulated rows into BigQuery with streaming API. */
  private void flushRows(
      TableReference tableReference,
      List<FailsafeValueInSingleWindow<TableRow, TableRow>> tableRows,
      List<String> uniqueIds,
      BigQueryOptions options,
      List<ValueInSingleWindow<ErrorT>> failedInserts)
      throws InterruptedException {
    if (!tableRows.isEmpty()) {
      try {
        long totalBytes =
            bqServices
                .getDatasetService(options)
                .insertAll(
                    tableReference,
                    tableRows,
                    uniqueIds,
                    retryPolicy,
                    failedInserts,
                    errorContainer,
                    skipInvalidRows,
                    ignoreUnknownValues,
                    ignoreInsertIds);
        byteCounter.inc(totalBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
