package com.linkedin.metadata.search.elasticsearch.indexbuilder;

import static com.linkedin.metadata.Constants.*;
import static com.linkedin.metadata.search.elasticsearch.indexbuilder.MappingsBuilder.PROPERTIES;

import com.google.common.collect.ImmutableMap;
import com.linkedin.metadata.config.search.ElasticSearchConfiguration;
import com.linkedin.metadata.search.utils.ESUtils;
import com.linkedin.metadata.timeseries.BatchWriteOperationsOptions;
import com.linkedin.metadata.version.GitVersion;
import com.linkedin.util.Pair;
import io.datahubproject.metadata.context.ObjectMapperContext;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.opensearch.OpenSearchException;
import org.opensearch.action.admin.cluster.node.tasks.list.ListTasksRequest;
import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.opensearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.opensearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.*;
import org.opensearch.client.core.CountRequest;
import org.opensearch.client.core.CountResponse;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.client.indices.GetIndexResponse;
import org.opensearch.client.indices.GetMappingsRequest;
import org.opensearch.client.indices.PutMappingRequest;
import org.opensearch.client.tasks.TaskSubmissionResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.reindex.ReindexRequest;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortBuilders;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.tasks.TaskInfo;

@Slf4j
public class ESIndexBuilder {

  private final RestHighLevelClient _searchClient;
  @Getter private final int numShards;

  @Getter private final int numReplicas;

  @Getter private final int numRetries;

  @Getter private final int refreshIntervalSeconds;

  @Getter private final Map<String, Map<String, String>> indexSettingOverrides;

  @Getter private final boolean enableIndexSettingsReindex;

  @Getter private final boolean enableIndexMappingsReindex;

  @Getter private final boolean enableStructuredPropertiesReindex;

  @Getter private final ElasticSearchConfiguration elasticSearchConfiguration;

  @Getter private final GitVersion gitVersion;

  @Getter private final int maxReindexHours;

  private static final RequestOptions REQUEST_OPTIONS =
      RequestOptions.DEFAULT.toBuilder()
          .setRequestConfig(RequestConfig.custom().setSocketTimeout(180 * 1000).build())
          .build();

  private final RetryRegistry retryRegistry;

  public ESIndexBuilder(
      RestHighLevelClient searchClient,
      int numShards,
      int numReplicas,
      int numRetries,
      int refreshIntervalSeconds,
      Map<String, Map<String, String>> indexSettingOverrides,
      boolean enableIndexSettingsReindex,
      boolean enableIndexMappingsReindex,
      boolean enableStructuredPropertiesReindex,
      ElasticSearchConfiguration elasticSearchConfiguration,
      GitVersion gitVersion) {
    this(
        searchClient,
        numShards,
        numReplicas,
        numRetries,
        refreshIntervalSeconds,
        indexSettingOverrides,
        enableIndexSettingsReindex,
        enableIndexMappingsReindex,
        enableStructuredPropertiesReindex,
        elasticSearchConfiguration,
        gitVersion,
        0);
  }

  public ESIndexBuilder(
      RestHighLevelClient searchClient,
      int numShards,
      int numReplicas,
      int numRetries,
      int refreshIntervalSeconds,
      Map<String, Map<String, String>> indexSettingOverrides,
      boolean enableIndexSettingsReindex,
      boolean enableIndexMappingsReindex,
      boolean enableStructuredPropertiesReindex,
      ElasticSearchConfiguration elasticSearchConfiguration,
      GitVersion gitVersion,
      int maxReindexHours) {
    this._searchClient = searchClient;
    this.numShards = numShards;
    this.numReplicas = numReplicas;
    this.numRetries = numRetries;
    this.refreshIntervalSeconds = refreshIntervalSeconds;
    this.indexSettingOverrides = indexSettingOverrides;
    this.enableIndexSettingsReindex = enableIndexSettingsReindex;
    this.enableIndexMappingsReindex = enableIndexMappingsReindex;
    this.elasticSearchConfiguration = elasticSearchConfiguration;
    this.enableStructuredPropertiesReindex = enableStructuredPropertiesReindex;
    this.gitVersion = gitVersion;
    this.maxReindexHours = maxReindexHours;

    RetryConfig config =
        RetryConfig.custom()
            .maxAttempts(Math.max(1, numRetries))
            .waitDuration(Duration.ofSeconds(10))
            .retryOnException(e -> e instanceof OpenSearchException)
            .failAfterMaxAttempts(true)
            .build();

    // Create a RetryRegistry with a custom global configuration
    this.retryRegistry = RetryRegistry.of(config);
  }

  /**
   * Utility function to check if the connected server is OpenSearch 2.9 or higher. Returns false if
   * the server is Elasticsearch or OpenSearch below version 2.9.
   *
   * @return true if the server is running OpenSearch 2.9 or higher, false otherwise
   * @throws IOException if there's an error communicating with the server
   */
  public boolean isOpenSearch29OrHigher() throws IOException {
    try {
      // We need to use the low-level client to get version information
      Response response = _searchClient.getLowLevelClient().performRequest(new Request("GET", "/"));
      Map<String, Object> responseMap =
          ObjectMapperContext.defaultMapper.readValue(response.getEntity().getContent(), Map.class);
      // Check if this is Elasticsearch: "You Know, for Search"
      String tagline = (String) responseMap.get("tagline");
      if (tagline.toLowerCase().contains("you know")) {
        return false;
      }
      // Get the version information
      Map<String, Object> versionInfo = (Map<String, Object>) responseMap.get("version");
      String versionString = (String) versionInfo.get("number");
      // Parse the version string
      String[] versionParts = versionString.split("\\.");
      if (versionParts.length < 2) {
        throw new IOException("Invalid version format: " + versionString);
      }
      int majorVersion = Integer.parseInt(versionParts[0]);
      int minorVersion = Integer.parseInt(versionParts[1]);
      // Return true if version is OpenSearch 2.9 or higher
      return majorVersion > 2 || (majorVersion == 2 && minorVersion >= 9);
    } catch (Exception e) {
      // return defensive false
      return false;
    }
  }

  public ReindexConfig buildReindexState(
      String indexName, Map<String, Object> mappings, Map<String, Object> settings)
      throws IOException {
    return buildReindexState(indexName, mappings, settings, false);
  }

  public ReindexConfig buildReindexState(
      String indexName,
      Map<String, Object> mappings,
      Map<String, Object> settings,
      boolean copyStructuredPropertyMappings)
      throws IOException {
    ReindexConfig.ReindexConfigBuilder builder =
        ReindexConfig.builder()
            .name(indexName)
            .enableIndexSettingsReindex(enableIndexSettingsReindex)
            .enableIndexMappingsReindex(enableIndexMappingsReindex)
            .enableStructuredPropertiesReindex(
                enableStructuredPropertiesReindex && !copyStructuredPropertyMappings)
            .version(gitVersion.getVersion());

    Map<String, Object> baseSettings = new HashMap<>(settings);
    baseSettings.put("number_of_shards", numShards);
    baseSettings.put("number_of_replicas", numReplicas);
    baseSettings.put("refresh_interval", String.format("%ss", refreshIntervalSeconds));
    // use zstd in OS only, in ES we can use it in the future with best_compression
    if (isOpenSearch29OrHigher()) {
      baseSettings.put("codec", "zstd_no_dict");
    }
    baseSettings.putAll(indexSettingOverrides.getOrDefault(indexName, Map.of()));
    Map<String, Object> targetSetting = ImmutableMap.of("index", baseSettings);
    builder.targetSettings(targetSetting);

    // Check if index exists
    boolean exists =
        _searchClient.indices().exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT);
    builder.exists(exists);

    // If index doesn't exist, no reindex
    if (!exists) {
      builder.targetMappings(mappings);
      return builder.build();
    }

    Settings currentSettings =
        _searchClient
            .indices()
            .getSettings(new GetSettingsRequest().indices(indexName), RequestOptions.DEFAULT)
            .getIndexToSettings()
            .values()
            .iterator()
            .next();
    builder.currentSettings(currentSettings);

    Map<String, Object> currentMappings =
        _searchClient
            .indices()
            .getMapping(new GetMappingsRequest().indices(indexName), RequestOptions.DEFAULT)
            .mappings()
            .values()
            .stream()
            .findFirst()
            .get()
            .getSourceAsMap();
    builder.currentMappings(currentMappings);

    if (copyStructuredPropertyMappings) {
      Map<String, Object> currentStructuredProperties =
          (Map<String, Object>)
              ((Map<String, Object>)
                      ((Map<String, Object>)
                              currentMappings.getOrDefault(PROPERTIES, new TreeMap()))
                          .getOrDefault(STRUCTURED_PROPERTY_MAPPING_FIELD, new TreeMap()))
                  .getOrDefault(PROPERTIES, new TreeMap());

      if (!currentStructuredProperties.isEmpty()) {
        HashMap<String, Map<String, Object>> props =
            (HashMap<String, Map<String, Object>>)
                ((Map<String, Object>) mappings.get(PROPERTIES))
                    .computeIfAbsent(
                        STRUCTURED_PROPERTY_MAPPING_FIELD,
                        (key) -> new HashMap<>(Map.of(PROPERTIES, new HashMap<>())));

        props.merge(
            PROPERTIES,
            currentStructuredProperties,
            (targetValue, currentValue) -> {
              HashMap<String, Object> merged = new HashMap<>(currentValue);
              merged.putAll(targetValue);
              return merged.isEmpty() ? null : merged;
            });
      }
    }

    builder.targetMappings(mappings);
    return builder.build();
  }

  /**
   * Builds index with given name, mappings and settings Deprecated: Use the
   * `buildIndex(ReindexConfig indexState) to enforce conventions via ReindexConfig class earlier in
   * the process.
   *
   * @param indexName index name
   * @param mappings ES mappings
   * @param settings ES settings
   * @throws IOException ES error
   */
  @Deprecated
  public void buildIndex(
      String indexName, Map<String, Object> mappings, Map<String, Object> settings)
      throws IOException {
    buildIndex(buildReindexState(indexName, mappings, settings));
  }

  public void buildIndex(ReindexConfig indexState) throws IOException {
    // If index doesn't exist, create index
    if (!indexState.exists()) {
      createIndex(indexState.name(), indexState);
      return;
    }
    log.info("Current mappings for index {}", indexState.name());
    log.info("{}", indexState.currentMappings());
    log.info("Target mappings for index {}", indexState.name());
    log.info("{}", indexState.targetMappings());

    // If there are no updates to mappings and settings, return
    if (!indexState.requiresApplyMappings() && !indexState.requiresApplySettings()) {
      log.info("No updates to index {}", indexState.name());
      return;
    }

    if (!indexState.requiresReindex()) {
      // no need to reindex and only new mappings or dynamic settings

      // Just update the additional mappings
      applyMappings(indexState, true);

      if (indexState.requiresApplySettings()) {
        UpdateSettingsRequest request = new UpdateSettingsRequest(indexState.name());
        Map<String, Object> indexSettings =
            ((Map<String, Object>) indexState.targetSettings().get("index"))
                .entrySet().stream()
                    .filter(e -> ReindexConfig.SETTINGS_DYNAMIC.contains(e.getKey()))
                    .collect(Collectors.toMap(e -> "index." + e.getKey(), Map.Entry::getValue));
        request.settings(indexSettings);

        boolean ack =
            _searchClient.indices().putSettings(request, RequestOptions.DEFAULT).isAcknowledged();
        log.info(
            "Updated index {} with new settings. Settings: {}, Acknowledged: {}",
            indexState.name(),
            ReindexConfig.OBJECT_MAPPER.writeValueAsString(indexSettings),
            ack);
      }
    } else {
      try {
        reindex(indexState);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Check if a specific index has 0 replicas and >0 documents, then increase its replica count to
   * 1.
   *
   * @param indexName The name of the index to check
   * @param dryRun If true, report what would happen without making changes
   * @return Map containing operation details
   * @throws IOException if there's an error communicating with Elasticsearch
   */
  private Map<String, Object> increaseReplicasForActiveIndices(String indexName, boolean dryRun)
      throws IOException {
    Map<String, Object> result = new HashMap<>();
    result.put("indexName", indexName);
    result.put("changed", false);
    result.put("dryRun", dryRun);
    GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
    GetIndexResponse response =
        _searchClient.indices().get(getIndexRequest, RequestOptions.DEFAULT);
    Map<String, Settings> indexToSettings = response.getSettings();
    Settings indexSettings = indexToSettings.get(indexName);
    int replicaCount = Integer.parseInt(indexSettings.get("index.number_of_replicas", "1"));
    CountRequest countRequest = new CountRequest(indexName);
    CountResponse countResponse = _searchClient.count(countRequest, RequestOptions.DEFAULT);
    long docCount = countResponse.getCount();
    result.put("currentReplicas", replicaCount);
    result.put("documentCount", docCount);
    // Check if index has 0 replicas and >0 documents
    if (replicaCount == 0 && docCount > 0) {
      if (!dryRun) {
        // Update replica count to X
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(indexName);
        Settings.Builder settingsBuilder =
            Settings.builder().put("index.number_of_replicas", getNumReplicas());
        updateSettingsRequest.settings(settingsBuilder);
        _searchClient.indices().putSettings(updateSettingsRequest, RequestOptions.DEFAULT);
      }
      result.put("changed", true);
      result.put("action", "Increase replicas from 0 to " + getNumReplicas());
    } else {
      result.put("action", "No change needed");
    }
    return result;
  }

  /**
   * Check if a specific index has 0 documents and replicas > 0, then set its replica count to 0.
   *
   * @param indexName The name of the index to check
   * @param dryRun If true, report what would happen without making changes
   * @return Map containing operation details
   * @throws IOException if there's an error communicating with Elasticsearch
   */
  private Map<String, Object> reduceReplicasForEmptyIndices(String indexName, boolean dryRun)
      throws IOException {
    Map<String, Object> result = new HashMap<>();
    result.put("indexName", indexName);
    result.put("changed", false);
    result.put("dryRun", dryRun);
    GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
    if (!_searchClient.indices().exists(getIndexRequest, RequestOptions.DEFAULT)) {
      result.put("skipped", true);
      result.put("reason", "Index does not exist");
      return result;
    }
    GetIndexResponse response =
        _searchClient.indices().get(getIndexRequest, RequestOptions.DEFAULT);
    Map<String, Settings> indexToSettings = response.getSettings();
    Settings indexSettings = indexToSettings.get(indexName);
    int replicaCount = Integer.parseInt(indexSettings.get("index.number_of_replicas", "1"));
    CountRequest countRequest = new CountRequest(indexName);
    CountResponse countResponse = _searchClient.count(countRequest, RequestOptions.DEFAULT);
    long docCount = countResponse.getCount();
    result.put("currentReplicas", replicaCount);
    result.put("documentCount", docCount);
    // Check if index has 0 documents and replicas > 0
    if (docCount == 0 && replicaCount > 0) {
      if (!dryRun) {
        // Set replica count to 0
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(indexName);
        Settings.Builder settingsBuilder = Settings.builder().put("index.number_of_replicas", 0);
        updateSettingsRequest.settings(settingsBuilder);
        _searchClient.indices().putSettings(updateSettingsRequest, RequestOptions.DEFAULT);
      }
      result.put("changed", true);
      result.put("action", "Decrease replicas from " + replicaCount + " to 0");
    } else {
      result.put("action", "No change needed");
    }
    return result;
  }

  public String createOperationSummary(
      Map<String, Object> increaseResult, Map<String, Object> reduceResult) {
    StringBuilder summary = new StringBuilder();
    String indexName = (String) increaseResult.get("indexName");
    boolean dryRun = (Boolean) increaseResult.get("dryRun");
    summary
        .append("Index: ")
        .append(indexName)
        .append(" (")
        .append(dryRun ? "DRY RUN" : "LIVE")
        .append(")\n");
    // Document count info
    long docCount = (long) increaseResult.get("documentCount");
    summary
        .append("Status: ")
        .append(docCount > 0 ? "Active" : "Empty")
        .append(" (")
        .append(docCount)
        .append(" docs)\n");
    // Replica changes summary
    int currentReplicas = (int) increaseResult.get("currentReplicas");
    summary.append("Replicas: ").append(currentReplicas).append(" → ");
    boolean increased =
        increaseResult.containsKey("changed") && (Boolean) increaseResult.get("changed");
    boolean reduced = reduceResult.containsKey("changed") && (Boolean) reduceResult.get("changed");
    if (increased) {
      summary.append("" + getNumReplicas() + " (increased)");
    } else if (reduced) {
      summary.append("0 (reduced)");
    } else {
      summary.append(currentReplicas).append(" (unchanged)");
    }
    // Add reason if no change
    if (!increased && !reduced) {
      if (docCount > 0 && currentReplicas > 0) {
        summary.append(" - already optimized");
      } else if (docCount == 0 && currentReplicas == 0) {
        summary.append(" - already optimized");
      }
    }
    return summary.toString();
  }

  public void tweakReplicas(ReindexConfig indexState, boolean dryRun) throws IOException {
    Map<String, Object> result = increaseReplicasForActiveIndices(indexState.name(), dryRun);
    Map<String, Object> resultb = reduceReplicasForEmptyIndices(indexState.name(), dryRun);
    log.info(
        "Tweaked replicas index {}: {}",
        indexState.name(),
        createOperationSummary(result, resultb));
  }

  /**
   * Apply mappings changes if reindex is not required
   *
   * @param indexState the state of the current and target index settings/mappings
   * @param suppressError during reindex logic this is not an error, for structured properties it is
   *     an error
   * @throws IOException communication issues with ES
   */
  public void applyMappings(ReindexConfig indexState, boolean suppressError) throws IOException {
    if (indexState.isPureMappingsAddition() || indexState.isPureStructuredPropertyAddition()) {
      log.info("Updating index {} mappings in place.", indexState.name());
      PutMappingRequest request =
          new PutMappingRequest(indexState.name()).source(indexState.targetMappings());
      _searchClient.indices().putMapping(request, RequestOptions.DEFAULT);
      log.info("Updated index {} with new mappings", indexState.name());
    } else {
      if (!suppressError) {
        log.error(
            "Attempted to apply invalid mappings. Current: {} Target: {}",
            indexState.currentMappings(),
            indexState.targetMappings());
      }
    }
  }

  public String reindexInPlaceAsync(
      String indexAlias,
      @Nullable QueryBuilder filterQuery,
      BatchWriteOperationsOptions options,
      ReindexConfig config)
      throws Exception {
    GetAliasesResponse aliasesResponse =
        _searchClient.indices().getAlias(new GetAliasesRequest(indexAlias), RequestOptions.DEFAULT);
    if (aliasesResponse.getAliases().isEmpty()) {
      throw new IllegalArgumentException(
          String.format("Input to reindexInPlaceAsync should be an alias. %s is not", indexAlias));
    }

    // Point alias at new index
    String nextIndexName = getNextIndexName(indexAlias, System.currentTimeMillis());
    createIndex(nextIndexName, config);
    renameReindexedIndices(_searchClient, indexAlias, null, nextIndexName, false);

    return submitReindex(
        aliasesResponse.getAliases().keySet().toArray(new String[0]),
        nextIndexName,
        options.getBatchSize(),
        TimeValue.timeValueSeconds(options.getTimeoutSeconds()),
        filterQuery);
  }

  private static String getNextIndexName(String base, long startTime) {
    return base + "_" + startTime;
  }

  private void reindex(ReindexConfig indexState) throws Throwable {
    final long startTime = System.currentTimeMillis();

    final long initialCheckIntervalMilli = 1000;
    final long finalCheckIntervalMilli = 60000;
    final long timeoutAt =
        maxReindexHours > 0 ? startTime + (1000L * 60 * 60 * maxReindexHours) : Long.MAX_VALUE;

    String tempIndexName = getNextIndexName(indexState.name(), startTime);

    try {
      Optional<TaskInfo> previousTaskInfo = getTaskInfoByHeader(indexState.name());

      String parentTaskId;
      if (previousTaskInfo.isPresent()) {
        log.info(
            "Reindex task {} in progress with description {}. Attempting to continue task from breakpoint.",
            previousTaskInfo.get().getTaskId(),
            previousTaskInfo.get().getDescription());
        parentTaskId = previousTaskInfo.get().getParentTaskId().toString();
        tempIndexName =
            ESUtils.extractTargetIndex(
                previousTaskInfo.get().getHeaders().get(ESUtils.OPAQUE_ID_HEADER));
      } else {
        // Create new index
        createIndex(tempIndexName, indexState);

        parentTaskId = submitReindex(indexState.name(), tempIndexName);
      }

      int reindexCount = 1;
      int count = 0;
      boolean reindexTaskCompleted = false;
      Pair<Long, Long> documentCounts = getDocumentCounts(indexState.name(), tempIndexName);
      long documentCountsLastUpdated = System.currentTimeMillis();
      long previousDocCount = documentCounts.getSecond();
      long estimatedMinutesRemaining = 0;

      while (System.currentTimeMillis() < timeoutAt) {
        log.info(
            "Task: {} - Reindexing from {} to {} in progress...",
            parentTaskId,
            indexState.name(),
            tempIndexName);

        Pair<Long, Long> tempDocumentsCount = getDocumentCounts(indexState.name(), tempIndexName);
        if (!tempDocumentsCount.equals(documentCounts)) {
          long currentTime = System.currentTimeMillis();
          long timeElapsed = currentTime - documentCountsLastUpdated;
          long docsIndexed = tempDocumentsCount.getSecond() - previousDocCount;

          // Calculate indexing rate (docs per millisecond)
          double indexingRate = timeElapsed > 0 ? (double) docsIndexed / timeElapsed : 0;

          // Calculate remaining docs and estimated time
          long remainingDocs = tempDocumentsCount.getFirst() - tempDocumentsCount.getSecond();
          long estimatedMillisRemaining =
              indexingRate > 0 ? (long) (remainingDocs / indexingRate) : 0;
          estimatedMinutesRemaining = estimatedMillisRemaining / (1000 * 60);

          documentCountsLastUpdated = currentTime;
          documentCounts = tempDocumentsCount;
          previousDocCount = documentCounts.getSecond();
        }

        if (documentCounts.getFirst().equals(documentCounts.getSecond())) {
          log.info(
              "Task: {} - Reindexing {} to {} task was successful",
              parentTaskId,
              indexState.name(),
              tempIndexName);
          reindexTaskCompleted = true;
          break;

        } else {
          float progressPercentage =
              100 * (1.0f * documentCounts.getSecond()) / documentCounts.getFirst();
          log.warn(
              "Task: {} - Document counts do not match {} != {}. Complete: {}%. Estimated time remaining: {} minutes",
              parentTaskId,
              documentCounts.getFirst(),
              documentCounts.getSecond(),
              progressPercentage,
              estimatedMinutesRemaining);

          long lastUpdateDelta = System.currentTimeMillis() - documentCountsLastUpdated;
          if (lastUpdateDelta > (300 * 1000)) {
            if (reindexCount <= numRetries) {
              log.warn(
                  "No change in index count after 5 minutes, re-triggering reindex #{}.",
                  reindexCount);
              submitReindex(indexState.name(), tempIndexName);
              reindexCount = reindexCount + 1;
              documentCountsLastUpdated = System.currentTimeMillis(); // reset timer
            } else {
              log.warn("Reindex retry timeout for {}.", indexState.name());
              break;
            }
          }

          count = count + 1;
          Thread.sleep(Math.min(finalCheckIntervalMilli, initialCheckIntervalMilli * count));
        }
      }

      if (!reindexTaskCompleted) {
        if (elasticSearchConfiguration.getBuildIndices().isAllowDocCountMismatch()
            && elasticSearchConfiguration.getBuildIndices().isCloneIndices()) {
          log.warn(
              "Index: {} - Post-reindex document count is different, source_doc_count: {} reindex_doc_count: {}\n"
                  + "This condition is explicitly ALLOWED, please refer to latest clone if original index is required.",
              indexState.name(),
              documentCounts.getFirst(),
              documentCounts.getSecond());
        } else {
          log.error(
              "Index: {} - Post-reindex document count is different, source_doc_count: {} reindex_doc_count: {}",
              indexState.name(),
              documentCounts.getFirst(),
              documentCounts.getSecond());
          diff(
              indexState.name(),
              tempIndexName,
              Math.max(documentCounts.getFirst(), documentCounts.getSecond()));
          throw new RuntimeException(
              String.format(
                  "Reindex from %s to %s failed. Document count %s != %s",
                  indexState.name(),
                  tempIndexName,
                  documentCounts.getFirst(),
                  documentCounts.getSecond()));
        }
      }
    } catch (Throwable e) {
      log.error(
          "Failed to reindex {} to {}: Exception {}",
          indexState.name(),
          tempIndexName,
          e.toString());
      _searchClient
          .indices()
          .delete(new DeleteIndexRequest().indices(tempIndexName), RequestOptions.DEFAULT);
      throw e;
    }

    log.info("Reindex from {} to {} succeeded", indexState.name(), tempIndexName);
    renameReindexedIndices(
        _searchClient, indexState.name(), indexState.indexPattern(), tempIndexName, true);
    log.info("Finished setting up {}", indexState.name());
  }

  public static void renameReindexedIndices(
      RestHighLevelClient searchClient,
      String originalName,
      @Nullable String pattern,
      String newName,
      boolean deleteOld)
      throws IOException {
    GetAliasesRequest getAliasesRequest = new GetAliasesRequest(originalName);
    if (pattern != null) {
      getAliasesRequest.indices(pattern);
    }
    GetAliasesResponse aliasesResponse =
        searchClient.indices().getAlias(getAliasesRequest, RequestOptions.DEFAULT);

    // If not aliased, delete the original index
    final Collection<String> aliasedIndexDelete;
    if (aliasesResponse.getAliases().isEmpty()) {
      log.info("Deleting index {} to allow alias creation", originalName);
      aliasedIndexDelete = List.of(originalName);
    } else {
      log.info("Deleting old indices in existing alias {}", aliasesResponse.getAliases().keySet());
      aliasedIndexDelete = aliasesResponse.getAliases().keySet();
    }

    // Add alias for the new index
    AliasActions removeAction =
        deleteOld ? AliasActions.removeIndex() : AliasActions.remove().alias(originalName);
    removeAction.indices(aliasedIndexDelete.toArray(new String[0]));
    AliasActions addAction = AliasActions.add().alias(originalName).index(newName);
    searchClient
        .indices()
        .updateAliases(
            new IndicesAliasesRequest().addAliasAction(removeAction).addAliasAction(addAction),
            RequestOptions.DEFAULT);
  }

  private String submitReindex(
      String[] sourceIndices,
      String destinationIndex,
      int batchSize,
      @Nullable TimeValue timeout,
      @Nullable QueryBuilder sourceFilterQuery)
      throws IOException {
    ReindexRequest reindexRequest =
        new ReindexRequest()
            .setSourceIndices(sourceIndices)
            .setDestIndex(destinationIndex)
            .setMaxRetries(numRetries)
            .setAbortOnVersionConflict(false)
            .setSourceBatchSize(batchSize);
    if (timeout != null) {
      reindexRequest.setTimeout(timeout);
    }
    if (sourceFilterQuery != null) {
      reindexRequest.setSourceQuery(sourceFilterQuery);
    }

    RequestOptions requestOptions =
        ESUtils.buildReindexTaskRequestOptions(
            gitVersion.getVersion(), sourceIndices[0], destinationIndex);
    TaskSubmissionResponse reindexTask =
        _searchClient.submitReindexTask(reindexRequest, requestOptions);
    return reindexTask.getTask();
  }

  private String submitReindex(String sourceIndex, String destinationIndex) throws IOException {
    return submitReindex(new String[] {sourceIndex}, destinationIndex, 2500, null, null);
  }

  private Pair<Long, Long> getDocumentCounts(String sourceIndex, String destinationIndex)
      throws Throwable {
    // Check whether reindex succeeded by comparing document count
    // There can be some delay between the reindex finishing and count being fully up to date, so
    // try multiple times
    long originalCount = 0;
    long reindexedCount = 0;
    for (int i = 0; i < this.numRetries; i++) {
      // Check if reindex succeeded by comparing document counts
      originalCount =
          retryRegistry
              .retry("retrySourceIndexCount")
              .executeCheckedSupplier(() -> getCount(sourceIndex));
      reindexedCount =
          retryRegistry
              .retry("retryDestinationIndexCount")
              .executeCheckedSupplier(() -> getCount(destinationIndex));
      if (originalCount == reindexedCount) {
        break;
      }
      try {
        Thread.sleep(20 * 1000);
      } catch (InterruptedException e) {
        log.warn("Sleep interrupted");
      }
    }

    return Pair.of(originalCount, reindexedCount);
  }

  private Optional<TaskInfo> getTaskInfoByHeader(String indexName) throws Throwable {
    Retry retryWithDefaultConfig = retryRegistry.retry("getTaskInfoByHeader");

    return retryWithDefaultConfig.executeCheckedSupplier(
        () -> {
          ListTasksRequest listTasksRequest = new ListTasksRequest().setDetailed(true);
          List<TaskInfo> taskInfos =
              _searchClient.tasks().list(listTasksRequest, REQUEST_OPTIONS).getTasks();
          return taskInfos.stream()
              .filter(
                  info ->
                      ESUtils.prefixMatch(
                          info.getHeaders().get(ESUtils.OPAQUE_ID_HEADER),
                          gitVersion.getVersion(),
                          indexName))
              .findFirst();
        });
  }

  private void diff(String indexA, String indexB, long maxDocs) {
    if (maxDocs <= 100) {

      SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
      searchSourceBuilder.size(100);
      searchSourceBuilder.sort(SortBuilders.fieldSort("_id").order(SortOrder.ASC));

      SearchRequest indexARequest = new SearchRequest(indexA);
      indexARequest.source(searchSourceBuilder);
      SearchRequest indexBRequest = new SearchRequest(indexB);
      indexBRequest.source(searchSourceBuilder);

      try {
        SearchResponse responseA = _searchClient.search(indexARequest, RequestOptions.DEFAULT);
        SearchResponse responseB = _searchClient.search(indexBRequest, RequestOptions.DEFAULT);

        Set<String> actual =
            Arrays.stream(responseB.getHits().getHits())
                .map(SearchHit::getId)
                .collect(Collectors.toSet());

        log.error(
            "Missing {}",
            Arrays.stream(responseA.getHits().getHits())
                .filter(doc -> !actual.contains(doc.getId()))
                .map(SearchHit::getSourceAsString)
                .collect(Collectors.toSet()));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private long getCount(@Nonnull String indexName) throws IOException {
    return _searchClient
        .count(
            new CountRequest(indexName).query(QueryBuilders.matchAllQuery()),
            RequestOptions.DEFAULT)
        .getCount();
  }

  private void createIndex(String indexName, ReindexConfig state) throws IOException {
    log.info("Index {} does not exist. Creating", indexName);
    CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
    createIndexRequest.mapping(state.targetMappings());
    createIndexRequest.settings(state.targetSettings());
    _searchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
    log.info("Created index {}", indexName);
  }

  public static void cleanIndex(
      RestHighLevelClient searchClient,
      ElasticSearchConfiguration esConfig,
      ReindexConfig indexState) {
    log.info(
        "Checking for orphan index pattern {} older than {} {}",
        indexState.indexPattern(),
        esConfig.getBuildIndices().getRetentionValue(),
        esConfig.getBuildIndices().getRetentionUnit());

    getOrphanedIndices(searchClient, esConfig, indexState)
        .forEach(
            orphanIndex -> {
              log.warn("Deleting orphan index {}.", orphanIndex);
              try {
                searchClient
                    .indices()
                    .delete(new DeleteIndexRequest().indices(orphanIndex), RequestOptions.DEFAULT);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
  }

  private static List<String> getOrphanedIndices(
      RestHighLevelClient searchClient,
      ElasticSearchConfiguration esConfig,
      ReindexConfig indexState) {
    List<String> orphanedIndices = new ArrayList<>();
    try {
      Date retentionDate =
          Date.from(
              Instant.now()
                  .minus(
                      Duration.of(
                          esConfig.getBuildIndices().getRetentionValue(),
                          ChronoUnit.valueOf(esConfig.getBuildIndices().getRetentionUnit()))));

      GetIndexResponse response =
          searchClient
              .indices()
              .get(new GetIndexRequest(indexState.indexCleanPattern()), RequestOptions.DEFAULT);

      for (String index : response.getIndices()) {
        var creationDateStr = response.getSetting(index, "index.creation_date");
        var creationDateEpoch = Long.parseLong(creationDateStr);
        var creationDate = new Date(creationDateEpoch);

        if (creationDate.after(retentionDate)) {
          continue;
        }

        if (response.getAliases().containsKey(index)
            && response.getAliases().get(index).size() == 0) {
          log.info("Index {} is orphaned", index);
          orphanedIndices.add(index);
        }
      }
    } catch (Exception e) {
      if (e.getMessage().contains("index_not_found_exception")) {
        log.info("No orphaned indices found with pattern {}", indexState.indexCleanPattern());
      } else {
        log.error(
            "An error occurred when trying to identify orphaned indices. Exception: {}",
            e.getMessage());
      }
    }
    return orphanedIndices;
  }
}
