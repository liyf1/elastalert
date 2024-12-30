package alertmanager.elastalert;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * ES告警管理器 - 负责告警的存储、去重、限流等管理功能
 */
public class ESAlertManager {
    private static final Logger logger = LoggerFactory.getLogger(ESAlertManager.class);

    private final ElasticsearchClient esClient;
    private final String alertIndex;
    private final ObjectMapper objectMapper;
    private final ExecutorService alertExecutor;
    private final ScheduledExecutorService maintenanceExecutor;

    // 告警去重缓存
    private final AlertCache alertCache;
    private static final Duration ALERT_CACHE_TTL = Duration.ofHours(24);

    // 限流器
    private final Map<String, RateLimiter> rateLimiters;

    // 批量写入队列
    private final BlockingQueue<AlertRecord> pendingAlerts;
    private static final int BULK_SIZE = 100;
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(10);

    // 告警统计
    private final Map<String, AlertStats> alertStats;

    public ESAlertManager(ElasticsearchClient esClient, String alertIndex) {
        this.esClient = esClient;
        this.alertIndex = alertIndex;
        this.objectMapper = new ObjectMapper();
        this.alertExecutor = new ThreadPoolExecutor(
                5, 10, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("alert-executor-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();

        // 初始化缓存
//        this.alertCache = CacheBuilder.newBuilder()
//                .expireAfterWrite(24, TimeUnit.HOURS)
//                .maximumSize(10000)
//                .build();
        this.alertCache = new ESAlertCache(esClient);

        this.rateLimiters = new ConcurrentHashMap<>();
        this.pendingAlerts = new LinkedBlockingQueue<>(5000);
        this.alertStats = new ConcurrentHashMap<>();

        // 启动维护任务
        startMaintenanceTasks();
    }

    /**
     * 处理告警
     */
    public void handleAlert(AlertContext context, Rule rule) {
        String alertId = generateAlertId(context, rule);

        try {
            // 1. 检查去重
            if (isDuplicate(alertId, context, rule)) {
                logger.debug("告警重复，已忽略: {}", alertId);
                return;
            }

            // 2. 检查限流
            if (isRateLimited(rule.getName())) {
                logger.debug("告警被限流: {}", alertId);
                return;
            }

            // 3. 异步处理告警
            alertExecutor.submit(() -> processAlert(alertId, context, rule));

        } catch (Exception e) {
            logger.error("处理告警失败: {}", alertId, e);
            recordAlertFailure(alertId, e);
        }
    }

    /**
     * 处理单个告警
     */
    private void processAlert(String alertId, AlertContext context, Rule rule) {
        try {
            // 1. 创建告警记录
            AlertRecord record = createAlertRecord(alertId, context, rule);

            // 2. 执行告警动作
            executeAlertActions(context, rule);

            // 3. 添加到待写入队列
            if (!pendingAlerts.offer(record)) {
                logger.warn("告警队列已满，直接写入: {}", alertId);
                writeAlertRecord(record);
            }

            // 4. 更新统计
            updateAlertStats(rule.getName(), true);

            // 5. 更新缓存
            updateAlertCache(alertId, record);

        } catch (Exception e) {
            logger.error("处理告警失败: {}", alertId, e);
            recordAlertFailure(alertId, e);
        }
    }

    /**
     * 执行告警动作
     */
    private void executeAlertActions(AlertContext context, Rule rule) {
        for (AlertConfig alertConfig : rule.getAlerts()) {
            try {
                alertConfig.sendAlert(rule,context);
            } catch (Exception e) {
                logger.error("执行告警动作失败:{}", alertConfig.getType(), e);
            }
        }
    }

    /**
     * 批量写入告警记录
     */
    private void flushAlerts() {
        if (pendingAlerts.isEmpty()) {
            return;
        }

        List<AlertRecord> records = new ArrayList<>();
        pendingAlerts.drainTo(records, BULK_SIZE);

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (AlertRecord record : records) {
                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(alertIndex)
                                .id(record.getId())
                                .document(record)
                        )
                );
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());

            if (response.errors()) {
                logger.error("批量写入告警失败: {}",
                        response
                );

                // 重试失败的记录
                for (AlertRecord record : records) {
                    try {
                        writeAlertRecord(record);
                    } catch (Exception ex) {
                        logger.error("重试写入告警失败: {}", record.getId(), ex);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("批量写入告警异常", e);
            // 重试所有记录
            for (AlertRecord record : records) {
                try {
                    writeAlertRecord(record);
                } catch (Exception ex) {
                    logger.error("重试写入告警失败: {}", record.getId(), ex);
                }
            }
        }
    }


    /**
     * 写入单个告警记录
     */
    private void writeAlertRecord(AlertRecord record) throws Exception {

        esClient.index(i -> i
                                .index(alertIndex)
                                .id(record.getId())
                                .document(record)
        );
    }
    /**
     * 检查是否重复告警
     */
    private boolean isDuplicate(String alertId, AlertContext context, Rule rule) {
        Optional<AlertEntry> existingAlert = alertCache.get(alertId);
        if (existingAlert.isEmpty()) {
            return false;
        }

        Duration realertTime = rule.getRealert();
        if (realertTime == null) {
            return false;
        }

        boolean withinWindow = Duration.between(existingAlert.get().getTimestamp(), Instant.now())
                .compareTo(realertTime) < 0;

        if (withinWindow) {
            logger.debug("Alert suppressed - within realert window. AlertId: {}, Last alert: {}, Realert time: {}",
                    alertId, existingAlert.get().getTimestamp(), realertTime);
        }

        return withinWindow;
    }

    /**
     * 检查是否被限流
     */
    private boolean isRateLimited(String ruleName) {
        RateLimiter limiter = rateLimiters.computeIfAbsent(ruleName,
                k -> RateLimiter.create(1.0)); // 默认1秒1个告警
        return !limiter.tryAcquire();
    }

    /**
     * 查询告警历史
     */
    public List<AlertRecord> queryAlerts(AlertQuery query) {
        try {
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
            searchBuilder.index(alertIndex);

            // 构建查询条件
            searchBuilder.query(q -> q
                    .bool(b -> {
                        if (query.getRuleName() != null) {
                            b.must(m -> m.term(t -> t.field("ruleName").value(query.getRuleName())));
                        }
                        if (query.getStartTime() != null && query.getEndTime() != null) {
                            b.must(m -> m.range(r -> r
                                    .date( d -> d.field("timestamp")
                                            .gte(query.getStartTime().toString())
                                            .lte(query.getEndTime().toString()))
                            ));
                        }
                        if (query.getStatus() != null) {
                            b.must(m -> m.term(t -> t.field("status").value(query.getStatus().toString())));
                        }
                        return b;
                    })
            );

            // 设置排序和大小
            searchBuilder.sort(s -> s.field(f -> f.field("timestamp").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)));
            searchBuilder.size(query.getSize());

            var response = esClient.search(searchBuilder.build(), AlertRecord.class);

            List<AlertRecord> alerts = new ArrayList<>();
            for (Hit<AlertRecord> hit : response.hits().hits()) {
                alerts.add(hit.source());
            }

            return alerts;
        } catch (Exception e) {
            logger.error("查询告警历史失败", e);
            throw new AlertException("查询告警历史失败", e);
        }
    }
    /**
     * 启动维护任务
     */
    private void startMaintenanceTasks() {
        // 定期刷新告警队列
        maintenanceExecutor.scheduleAtFixedRate(
                this::flushAlerts,
                FLUSH_INTERVAL.getSeconds(),
                FLUSH_INTERVAL.getSeconds(),
                TimeUnit.SECONDS
        );

        // 定期清理统计数据
        maintenanceExecutor.scheduleAtFixedRate(
                this::cleanupStats,
                1,
                1,
                TimeUnit.HOURS
        );
    }

    /**
     * 创建告警记录
     */
    private AlertRecord createAlertRecord(String alertId, AlertContext context, Rule rule) {
        AlertRecord record = new AlertRecord();
        record.setId(alertId);
        record.setRuleName(rule.getName());
        record.setRuleType(rule.getType());
        record.setTimestamp(Instant.now());
        record.setAlertSubject(context.getAlertSubject());
        record.setAlertText(context.getAlertText());
        record.setMatches(context.getMatches());
        record.setStatus(AlertStatus.SUCCESS);

        // 添加元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rule_category", rule.getCategory());
        metadata.put("rule_owner", rule.getOwner());
        metadata.put("rule_priority", rule.getPriority());
        metadata.put("source_index", rule.getIndex());
        metadata.put("alert_time", Instant.now().toString());
        metadata.put("query_key", context.getMatches().get(0).get(rule.getQueryKey()));
        record.setMetadata(metadata);

        return record;
    }

    /**
     * 生成告警ID - 移除时间戳，只使用规则相关的标识信息
     */
    private String generateAlertId(AlertContext context, Rule rule) {
        StringBuilder idBuilder = new StringBuilder();
        idBuilder.append(rule.getName());
        idBuilder.append("_").append(rule.getIndex());

        // 添加查询键值
        if (rule.getQueryKey() != null && !context.getMatches().isEmpty()) {
            Object queryKeyValue = context.getMatches().get(0).get(rule.getQueryKey());
            if (queryKeyValue != null) {
                idBuilder.append("_").append(queryKeyValue);
            }
        }

        // 添加聚合键值
        if (rule.getAggregationKey() != null && !context.getMatches().isEmpty()) {
            Object aggKeyValue = context.getMatches().get(0).get(rule.getAggregationKey());
            if (aggKeyValue != null) {
                idBuilder.append("_").append(aggKeyValue);
            }
        }

        // 添加其他需要用于区分告警唯一性的字段
        // 比如告警级别、告警类型等
        if (context.getAlertText() != null) {
            idBuilder.append("_").append(context.getAlertText());
        }

        return DigestUtils.md5Hex(idBuilder.toString());
    }
    /**
     * 记录告警失败
     */
    private void recordAlertFailure(String alertId, Exception error) {
        try {
            AlertRecord record = new AlertRecord();
            record.setId(alertId);
            record.setTimestamp(Instant.now());
            record.setStatus(AlertStatus.FAILURE);
            record.setErrorMessage(error.getMessage());

            // 获取完整的堆栈信息
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("stack_trace", sw.toString());
            record.setMetadata(metadata);

            // 直接写入ES，不经过队列
            writeAlertRecord(record);

            // 更新统计
            updateAlertStats(record.getRuleName(), false);

        } catch (Exception e) {
            logger.error("记录告警失败信息时发生错误", e);
        }
    }

    /**
     * 更新告警统计
     */
    private void updateAlertStats(String ruleName, boolean success) {
        AlertStats stats = alertStats.computeIfAbsent(ruleName, k -> new AlertStats());
        stats.setLastAlertTime(Instant.now());
        stats.setTotalCount(stats.getTotalCount() + 1);
        if (success) {
            stats.setSuccessCount(stats.getSuccessCount() + 1);
        } else {
            stats.setFailureCount(stats.getFailureCount() + 1);
        }
    }

    /**
     * 更新告警缓存
     */
    private void updateAlertCache(String alertId, AlertRecord record) {
        AlertEntry entry = new AlertEntry();
        entry.setId(alertId);
        entry.setRuleName(record.getRuleName());
        entry.setTimestamp(record.getTimestamp());

        Map<String, Object> context = new HashMap<>();
        context.put("alert_subject", record.getAlertSubject());
        if (record.getMatches() != null && !record.getMatches().isEmpty()) {
            context.put("matches", record.getMatches());
        }
        entry.setContext(context);

        alertCache.put(alertId, entry, ALERT_CACHE_TTL);
    }

    /**
     * 转换对象为Map (用于ES文档)
     */
    private Map<String, Object> writeValueAsMap(Object value) {
        try {
            return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new AlertException("转换对象到Map失败", e);
        }
    }

    /**
     * 清理统计数据
     */
    private void cleanupStats() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        alertStats.entrySet().removeIf(entry -> {
            AlertStats stats = entry.getValue();
            return stats.getLastAlertTime() != null &&
                    stats.getLastAlertTime().isBefore(cutoff);
        });
    }

    /**
     * 获取告警统计信息
     */
    public Map<String, AlertStats> getAlertStats() {
        return Collections.unmodifiableMap(alertStats);
    }

    /**
     * 获取特定规则的告警统计
     */
    public AlertStats getAlertStats(String ruleName) {
        return alertStats.get(ruleName);
    }

    /**
     * AlertStats类的完整定义
     */
    @Data
    public static class AlertStats {
        private long totalCount;
        private long successCount;
        private long failureCount;
        private Instant lastAlertTime;
        private Map<String, Integer> statusCounts;

        public AlertStats() {
            this.totalCount = 0;
            this.successCount = 0;
            this.failureCount = 0;
            this.statusCounts = new ConcurrentHashMap<>();
        }

        public synchronized void incrementStatusCount(AlertStatus status) {
            statusCounts.merge(status.name(), 1, Integer::sum);
        }

        public Map<String, Integer> getStatusBreakdown() {
            return Collections.unmodifiableMap(statusCounts);
        }
    }

    /**
     * 关闭管理器
     */
    public void shutdown() {
        try {
            // 停止接收新的告警
            alertExecutor.shutdown();
            if (!alertExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                alertExecutor.shutdownNow();
            }

            // 停止维护任务
            maintenanceExecutor.shutdown();
            if (!maintenanceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }

            // 最终刷新
            flushAlerts();
            alertCache.shutdown();

        } catch (Exception e) {
            logger.error("关闭告警管理器失败", e);
        }
    }

    // 内部类定义
    @Data
    public static class AlertRecord {
        private String id;
        private String ruleName;
        private String ruleType;
        private Instant timestamp;
        private String alertSubject;
        private String alertText;
        private List<Map<String, Object>> matches;
        private AlertStatus status;
        private String errorMessage;
        private Map<String, Object> metadata;
    }

    public enum AlertStatus {
        SUCCESS,
        FAILURE,
        SUPPRESSED
    }

    @Data
    public static class AlertQuery {
        private String ruleName;
        private Instant startTime;
        private Instant endTime;
        private AlertStatus status;
        private int size = 100;
    }
}