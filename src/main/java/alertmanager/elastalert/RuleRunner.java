package alertmanager.elastalert;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.DateRangeQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则执行器 - 负责执行单个规则的检查逻辑
 */
public class RuleRunner {
    private static final Logger logger = LoggerFactory.getLogger(RuleRunner.class);

    private final Rule rule;
    private final ElasticsearchClient esClient;
    private final ESAlertManager alertManager;
    private final Map<String, Object> ruleState;

    // 用于记录上次执行时间
    private Instant lastRunTime;

    // 用于frequency规则的匹配计数
    private final Map<String, Integer> matchCounts;

    public RuleRunner(Rule rule, ElasticsearchClient esClient, ESAlertManager alertManager) {
        this.rule = rule;
        this.esClient = esClient;
        this.alertManager = alertManager;
        this.ruleState = new ConcurrentHashMap<>();
        this.lastRunTime = Instant.now();
        this.matchCounts = new ConcurrentHashMap<>();
    }

    /**
     * 执行规则检查
     */
    public void execute() {
        logger.debug("开始执行规则: {}", rule.getName());

        try {
            Instant currentTime = Instant.now();
            Instant queryStartTime = getQueryStartTime();

            // 构建并执行查询
            SearchRequest searchRequest = buildQuery(queryStartTime, currentTime);
            SearchResponse<Map> response = esClient.search(searchRequest, Map.class);

            // 处理查询结果
            List<Map<String, Object>> matches = processResults(response);

            // 根据规则类型处理匹配
            switch (rule.getType()) {
                case "frequency":
                    handleFrequencyRule(matches);
                    break;
                case "spike":
                    handleSpikeRule(matches);
                    break;
                case "flatline":
                    handleFlatlineRule(matches);
                    break;
                default:
                    handleDefaultRule(matches);
            }

            // 更新状态
            lastRunTime = currentTime;

        } catch (Exception e) {
            logger.error("规则执行失败: {}", rule.getName(), e);
        }
    }

    /**
     * 构建ES查询
     */
    private SearchRequest buildQuery(Instant startTime, Instant endTime) {
        try {
            // 使用 builder 模式构建查询
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index(rule.getIndex());

            // 构建 bool 查询
            searchBuilder.query(q -> q
                    .bool(b -> {
                        // 添加时间范围过滤
                        b.must(m -> m
                                .range(r -> r
                                        .date(d -> d.field(rule.getTimestampField())
                                                    .gte(startTime.toString())
                                                    .lte(endTime.toString())
                                        )
                                )
                        );

                        // 添加规则中定义的过滤条件
                        for (Map<String, Object> filter : rule.getFilter()) {
                            if (filter.containsKey("term")) {
                                Map<String, Object> term = (Map<String, Object>) filter.get("term");
                                term.forEach((field, value) ->
                                        b.must(m -> m
                                                .term(t -> t
                                                        .field(field)
                                                        .value((FieldValue) JsonData.of(value))
                                                )
                                        )
                                );
                            } else if (filter.containsKey("query")) {
                                Map<String, Object> queryMap = (Map<String, Object>) filter.get("query");

                                // 处理 query_string 查询
                                if (queryMap.containsKey("query_string")) {
                                    Map<String, Object> queryStringMap = (Map<String, Object>) queryMap.get("query_string");
                                    if (queryStringMap.containsKey("query")) {
                                        String queryString = (String) queryStringMap.get("query");
                                        b.must(m -> m
                                                .queryString(qs -> qs
                                                        .query(queryString)
                                                )
                                        );
                                    }
                                }

                                // 处理 term 查询
                                if (queryMap.containsKey("term")) {
                                    Map<String, Object> termMap = (Map<String, Object>) queryMap.get("term");
                                    for (Map.Entry<String, Object> entry : termMap.entrySet()) {
                                        b.must(m -> m
                                                .term(t -> t
                                                        .field(entry.getKey())
                                                        .value((FieldValue) JsonData.of(entry.getValue()))
                                                )
                                        );
                                    }
                                }

                                // 处理 range 查询
                                if (queryMap.containsKey("range")) {
                                    Map<String, Object> rangeMap = (Map<String, Object>) queryMap.get("range");
                                    for (Map.Entry<String, Object> entry : rangeMap.entrySet()) {
                                        String field = entry.getKey();
                                        Map<String, Object> rangeSpec = (Map<String, Object>) entry.getValue();
                                        b.must(m -> m
                                                .range(r -> {
                                                    DateRangeQuery.Builder builder = new DateRangeQuery.Builder();
                                                    builder.field(field);
                                                    if (rangeSpec.containsKey("gte")) {
                                                        builder.gte(JsonData.of(rangeSpec.get("gte")).toString());
                                                    }
                                                    if (rangeSpec.containsKey("lte")) {
                                                        builder.lte(JsonData.of(rangeSpec.get("lte")).toString());
                                                    }
                                                    return r;
                                                })
                                        );
                                    }
                                }

                                // 处理 match 查询
                                if (queryMap.containsKey("match")) {
                                    Map<String, Object> matchMap = (Map<String, Object>) queryMap.get("match");
                                    for (Map.Entry<String, Object> entry : matchMap.entrySet()) {
                                        b.must(m -> m
                                                .match(mt -> mt
                                                        .field(entry.getKey())
                                                        .query(entry.getValue().toString())
                                                )
                                        );
                                    }
                                }
                            }
                        }
                        return b;
                    })
            );
            // 设置排序
            searchBuilder.sort(s -> s
                    .field(f -> f
                            .field(rule.getTimestampField())
                            .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)
                    )
            );

            // 设置需要返回的字段
            if (!rule.getInclude().isEmpty()) {
                searchBuilder.source(src -> src
                        .filter(f -> f
                                .includes(rule.getInclude())
                        )
                );
            }

            // 设置大小限制
            searchBuilder.size(rule.getMaxQuerySize());

            // 如果是聚合查询，添加聚合配置
            if (rule.getType().equals("frequency")) {
                searchBuilder.aggregations("timestamp_intervals", a -> a
                        .dateHistogram(h -> h
                                .field(rule.getTimestampField())
                                .fixedInterval(f -> f.time(rule.getTimeframe().getSeconds() + "s"))
                        )
                );


                // 如果有查询键，添加terms聚合
                if (rule.getQueryKey() != null) {
                    searchBuilder.aggregations("query_key_counts", a -> a
                            .terms(t -> t
                                    .field(rule.getQueryKey())
                                    .size(10000)
                            )
                    );
                }
            }

            return searchBuilder.build();

        } catch (Exception e) {
            logger.error("构建查询失败", e);
            throw new RuntimeException("构建查询失败", e);
        }
    }

    /**
     * 处理查询结果
     */
    private List<Map<String, Object>> processResults(SearchResponse<Map> response) {
        List<Map<String, Object>> matches = new ArrayList<>();

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source != null) {
                source.put("_id", hit.id());
                source.put("_index", hit.index());

                // 添加元数据
                if (rule.getQueryKey() != null) {
                    Object queryKeyValue = extractQueryKeyValue(source);
                    if (queryKeyValue != null) {
                        source.put("query_key", queryKeyValue);
                    }
                }

                matches.add(source);
            }
        }

        return matches;
    }

    /**
     * 处理Frequency类型规则
     */
    private void handleFrequencyRule(List<Map<String, Object>> matches) {
        if (matches.isEmpty()) {
            return;
        }

        // 按查询键分组计数
        Map<String, List<Map<String, Object>>> groupedMatches = new HashMap<>();

        for (Map<String, Object> match : matches) {
            String key = rule.getQueryKey() != null ?
                    extractQueryKeyValue(match).toString() : "_default_";
            groupedMatches.computeIfAbsent(key, k -> new ArrayList<>()).add(match);
        }

        // 检查每个分组是否超过阈值
        for (Map.Entry<String, List<Map<String, Object>>> entry : groupedMatches.entrySet()) {
            String key = entry.getKey();
            List<Map<String, Object>> groupMatches = entry.getValue();

            // 更新计数
            int currentCount = matchCounts.getOrDefault(key, 0) + groupMatches.size();
            matchCounts.put(key, currentCount);

            // 检查是否达到告警阈值
            if (currentCount >= rule.getNumEvents()) {
                // 触发告警
                AlertContext alertContext = createAlertContext(groupMatches);
                alertManager.handleAlert(alertContext, rule);

                // 重置计数
                matchCounts.remove(key);
            }
        }

        // 清理过期的计数
        cleanupOldCounts();
    }

    /**
     * 处理Spike类型规则
     */
    private void handleSpikeRule(List<Map<String, Object>> matches) {
        // Spike规则的具体实现...
    }

    /**
     * 处理Flatline类型规则
     */
    private void handleFlatlineRule(List<Map<String, Object>> matches) {
        // Flatline规则的具体实现...
    }

    /**
     * 处理默认规则类型
     */
    private void handleDefaultRule(List<Map<String, Object>> matches) {
        if (!matches.isEmpty()) {
            AlertContext alertContext = createAlertContext(matches);
            alertManager.handleAlert(alertContext, rule);
        }
    }

    /**
     * 创建告警上下文
     * @param matches 匹配的事件列表
     * @return 告警上下文
     */
    private AlertContext createAlertContext(List<Map<String, Object>> matches) {
        return AlertContext.builder()
                .alertId(generateAlertId())  // 生成唯一ID
                .createTime(Instant.now())   // 当前时间
                .ruleName(rule.getName())    // 规则名称
                .ruleType(rule.getType())    // 规则类型
                .ruleConfig(rule.toMap())    // 规则配置
                .matches(matches)            // 匹配事件
                .matchCount(matches.size())  // 匹配数量

                // 设置查询键信息
                .queryKey(rule.getQueryKey())
                .queryValue(getQueryKeyValue(matches))

                // 设置聚合键信息
                .aggregationKey(rule.getAggregationKey())
                .aggregationValue(getAggregationKeyValue(matches))

                // 设置告警内容
                .alertSubject(formatAlertSubject(matches))
                .alertText(formatAlertText(matches))

                // 设置元数据
                .metadata(createMetadata(matches))

                // 设置标签
                .tags(createTags())

                // 设置优先级
                .priority(AlertContext.AlertPriority.fromString(rule.getPriority()))

                // 设置告警配置
                .alertConfig(createAlertConfig())

                // 设置接收者
                .recipients(getRecipients())

                .build();
    }

    /**
     * 获取查询键值
     */
    private Object getQueryKeyValue(List<Map<String, Object>> matches) {
        if (rule.getQueryKey() == null || matches.isEmpty()) {
            return null;
        }
        return extractFieldValue(matches.get(0), rule.getQueryKey());
    }

    /**
     * 获取聚合键值
     */
    private Object getAggregationKeyValue(List<Map<String, Object>> matches) {
        if (rule.getAggregationKey() == null || matches.isEmpty()) {
            return null;
        }
        return extractFieldValue(matches.get(0), rule.getAggregationKey());
    }

    /**
     * 创建元数据
     */
    private Map<String, Object> createMetadata(List<Map<String, Object>> matches) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rule_name", rule.getName());
        metadata.put("rule_type", rule.getType());
        metadata.put("rule_owner", rule.getOwner());
        metadata.put("rule_category", rule.getCategory());
        metadata.put("source_index", rule.getIndex());
        metadata.put("alert_time", Instant.now().toString());
        metadata.put("match_count", matches.size());

        // 添加查询统计信息
        if (!matches.isEmpty()) {
            metadata.put("first_match_time", extractFieldValue(matches.get(0), rule.getTimestampField()));
            metadata.put("last_match_time", extractFieldValue(matches.get(matches.size()-1), rule.getTimestampField()));
        }
//
//        // 添加自定义元数据
//        if (rule.getMetadata() != null) {
//            metadata.putAll(rule.getMetadata());
//        }

        return metadata;
    }

    /**
     * 创建标签
     */
    private Map<String, String> createTags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("rule_type", rule.getType());
        tags.put("priority", rule.getPriority());
        tags.put("category", rule.getCategory());
//        tags.put("environment", rule.getEnvironment());
//
//        // 添加自定义标签
//        if (rule.getTags() != null) {
//            tags.putAll(rule.getTags());
//        }
//
        return tags;
    }

    /**
     * 创建告警配置
     */
    private Map<String, Object> createAlertConfig() {
        Map<String, Object> config = new HashMap<>();

        // 邮件配置
        if (rule.getEmailTo() != null) {
            Map<String, Object> emailConfig = new HashMap<>();
            emailConfig.put("to", rule.getEmailTo());
            emailConfig.put("from", rule.getEmailFromField());
            emailConfig.put("smtp_host", rule.getSmtpHost());
            emailConfig.put("smtp_port", rule.getSmtpPort());
            config.put("email", emailConfig);
        }

        // Slack配置
        if (rule.getSlackWebhookUrl() != null) {
            Map<String, Object> slackConfig = new HashMap<>();
            slackConfig.put("webhook_url", rule.getSlackWebhookUrl());
            slackConfig.put("channel", rule.getSlackChannel());
            slackConfig.put("username", rule.getSlackUsername());
            slackConfig.put("emoji", rule.getSlackEmoji());
            slackConfig.put("msg_color", rule.getSlackMsgColor());
            config.put("slack", slackConfig);
        }

        // Webhook配置
        if (rule.getWebhookUrl() != null) {
            Map<String, Object> webhookConfig = new HashMap<>();
            webhookConfig.put("url", rule.getWebhookUrl());
            webhookConfig.put("headers", rule.getWebhookHeaders());
            webhookConfig.put("payload", rule.getWebhookPayload());
            config.put("webhook", webhookConfig);
        }


        return config;
    }

    /**
     * 获取接收者列表
     */
    private List<String> getRecipients() {
        List<String> recipients = new ArrayList<>();

        // 添加邮件接收者
        if (rule.getEmailTo() != null) {
            recipients.addAll(rule.getEmailTo());
        }

        // 添加Slack频道
        if (rule.getSlackChannel() != null) {
            recipients.add(rule.getSlackChannel());
        }
        if (rule.getDingdingUsers() != null){
            recipients.addAll(rule.getDingdingUsers());
        }

        return recipients;
    }

    /**
     * 生成告警ID
     */
    private String generateAlertId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 从文档中提取字段值
     */
    private Object extractFieldValue(Map<String, Object> doc, String field) {
        if (field == null || doc == null) {
            return null;
        }

        String[] parts = field.split("\\.");
        Object current = doc;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * 格式化告警主题
     */
    private String formatAlertSubject(List<Map<String, Object>> matches) {
        String subject = rule.getAlertSubject();
        if (subject == null) {
            return rule.getName() + " Alert";
        }

        // 替换模板变量
        Map<String, Object> match = matches.get(0);
        for (String arg : rule.getAlertSubjectArgs()) {
            String value = getFieldValue(match, arg);
            subject = subject.replace("{" + arg + "}", value);
        }

        return subject;
    }

    private String getFieldValue(Map<String, Object> doc, String field) {
        if (field == null || doc == null){
            return "";
        }
        return String.valueOf(doc.get(field));
    }

    /**
     * 格式化告警内容
     */
    private String formatAlertText(List<Map<String, Object>> matches) {
        String text = rule.getAlertText();
        if (text == null) {
            return "Matched " + matches.size() + " events";
        }

        // 替换模板变量
        Map<String, Object> match = matches.get(0);
        for (String arg : rule.getAlertTextArgs()) {
            String value = getFieldValue(match, arg);
            text = text.replace("{" + arg + "}", value);
        }

        return text;
    }

    /**
     * 获取查询开始时间
     */
    private Instant getQueryStartTime() {
        return Instant.now().minus(rule.getTimeframe());
    }

    /**
     * 清理过期的计数
     */
    private void cleanupOldCounts() {
        Instant cutoff = Instant.now().minus(rule.getTimeframe());
        matchCounts.entrySet().removeIf(entry ->
                ruleState.containsKey("last_match_" + entry.getKey()) &&
                        ((Instant) ruleState.get("last_match_" + entry.getKey())).isBefore(cutoff)
        );
    }

    /**
     * 从文档中提取字段值
     */
    private String extractField(Map<String, Object> doc, String field) {
        String[] parts = field.split("\\.");
        Object current = doc;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return "";
            }
        }

        return current != null ? current.toString() : "";
    }

    /**
     * 从文档中提取查询键值
     */
    private Object extractQueryKeyValue(Map<String, Object> doc) {
        if (rule.getQueryKey() == null) {
            return null;
        }
        return extractField(doc, rule.getQueryKey());
    }
}