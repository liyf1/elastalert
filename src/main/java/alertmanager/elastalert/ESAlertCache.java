package alertmanager.elastalert;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


@Slf4j
public class ESAlertCache implements AlertCache {
    private static final String DEDUP_INDEX = "alert-dedup";

    private final ElasticsearchClient esClient;

    public ESAlertCache(ElasticsearchClient esClient) {
        this.esClient = esClient;
        ensureDedupIndexExists();
    }

    @Override
    public Optional<AlertEntry> get(String alertId) {
        try {
            var response = esClient.get(g -> g
                            .index(DEDUP_INDEX)
                            .id(alertId),
                    JsonData.class  // ES8中使用JsonData处理动态内容
            );

            if (!response.found()) {
                return Optional.empty();
            }

            var source = response.source();
            if (source == null) {
                return Optional.empty();
            }

            // 将JsonData转换为Map
            JsonObject sourceMap = source.toJson().asJsonObject();

            // 手动构建AlertEntry对象
            AlertEntry entry = new AlertEntry();
            entry.setId(getString(sourceMap, "id"));
            entry.setRuleName(getString(sourceMap, "ruleName"));

            // 处理时间字段
            String timestampStr = getString(sourceMap, "timestamp");
            if (timestampStr != null) {
                entry.setTimestamp(Instant.parse(timestampStr));
            }

            // 处理context字段
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) sourceMap.get("context");
            entry.setContext(context);

            // 处理retryCount字段
            JsonNumber retryCount = (JsonNumber) sourceMap.get("retryCount");
            entry.setRetryCount(retryCount != null ? retryCount.intValue() : 0);

            return Optional.of(entry);

        } catch (Exception e) {
            log.error("Failed to get alert from ES: {}", alertId, e);
            return Optional.empty();
        }
    }

    private <K> String getString(Map<? super K, ?> map, K key) {
        if (map != null) {
            Object answer = map.get(key);
            if (answer != null) {
                return answer.toString().replaceAll("^\"|\"$", "");
            }
        }

        return null;
    }

    @Override
    public void put(String alertId, AlertEntry entry, Duration ttl) {
        try {
            // 转换为Map并确保日期格式正确
            Map<String, Object> document = new HashMap<>();
            document.put("id", entry.getId());
            document.put("ruleName", entry.getRuleName());
            document.put("context", entry.getContext());
            document.put("retryCount", entry.getRetryCount());
            // 确保时间字段使用ISO格式
            document.put("timestamp", entry.getTimestamp().toString());
            document.put("expirationTime", Instant.now().plus(ttl).toString());

            esClient.index(i -> i
                    .index(DEDUP_INDEX)
                    .id(alertId)
                    .document(document)
            );
        } catch (Exception e) {
            log.error("Failed to save alert to ES: {}", alertId, e);
        }
    }

    @Override
    public boolean exists(String alertId) {
        try {
            return esClient.exists(e -> e
                    .index(DEDUP_INDEX)
                    .id(alertId)
            ).value();
        } catch (Exception e) {
            log.error("Failed to check alert existence in ES: {}", alertId, e);
            return false;
        }
    }

    @Override
    public void delete(String alertId) {
        try {
            esClient.delete(d -> d
                    .index(DEDUP_INDEX)
                    .id(alertId)
            );
        } catch (Exception e) {
            log.error("Failed to delete alert from ES: {}", alertId, e);
        }
    }

    @Override
    public void cleanup() {
        // 由ES的ILM处理，无需实现
    }

    @Override
    public void shutdown() {
        // ES client由外部管理，无需实现
    }

    private void ensureDedupIndexExists() {
        try {
            boolean exists = esClient.indices().exists(req -> req.index(DEDUP_INDEX)).value();
            if (!exists) {
                esClient.indices().create(req -> req
                        .index(DEDUP_INDEX)
                        .mappings(m -> m
                                .properties("id", p -> p.keyword(f -> f))
                                .properties("timestamp", p -> p
                                        .date(d -> d.format("strict_date_time||strict_date_optional_time||epoch_millis"))
                                )
                                .properties("expirationTime", p -> p
                                        .date(d -> d.format("strict_date_time||strict_date_optional_time||epoch_millis"))
                                )
                                .properties("ruleName", p -> p.keyword(k -> k))
                                .properties("context", p -> p.object(o -> o))
                                .properties("status", p -> p.keyword(f -> f))
                                .properties("retryCount", p -> p.integer(f -> f))
                        )
                );
            }
        } catch (Exception e) {
            log.error("Failed to create dedup index", e);
        }
    }

    private void createDedupIlmPolicy() {
        // ILM策略创建代码保持不变
    }
}

