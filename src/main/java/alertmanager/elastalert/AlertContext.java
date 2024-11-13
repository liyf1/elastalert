package alertmanager.elastalert;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 告警上下文 - 包含告警产生和处理所需的所有信息
 */
@Data
@Builder
public class AlertContext {
    // 基本信息
    private final String alertId;              // 告警唯一ID
    private final Instant createTime;          // 告警创建时间

    // 规则信息
    private final String ruleName;             // 规则名称
    private final String ruleType;             // 规则类型
    private final Map<String, Object> ruleConfig; // 规则配置

    // 告警内容
    private final String alertSubject;         // 告警主题
    private final String alertText;            // 告警正文
    private final List<Map<String, Object>> matches; // 匹配的事件

    // 聚合信息
    private final String aggregationKey;       // 聚合键
    private final Object aggregationValue;     // 聚合值
    private final int matchCount;              // 匹配数量

    // 查询信息
    private final String queryKey;             // 查询键
    private final Object queryValue;           // 查询值
    private final Map<String, Object> searchMetadata; // 搜索元数据

    // 告警元数据
    private final Map<String, Object> metadata;    // 自定义元数据
    private final Map<String, String> tags;        // 标签
    private final AlertPriority priority;          // 告警优先级

    // 告警目标
    private final List<String> recipients;     // 接收者列表
    private final Map<String, Object> alertConfig; // 告警配置

    /**
     * 创建构建器的静态方法
     */
    public static AlertContext.AlertContextBuilder builder() {
        return new AlertContext.AlertContextBuilder();
    }

    /**
     * 从规则和匹配创建告警上下文
     */
    public static AlertContext fromRule(Rule rule, List<Map<String, Object>> matches) {
        AlertContext.AlertContextBuilder builder = AlertContext.builder()
                .alertId(UUID.randomUUID().toString())
                .createTime(Instant.now())
                .ruleName(rule.getName())
                .ruleType(rule.getType())
                .ruleConfig(rule.toMap())
                .matches(matches)
                .matchCount(matches.size());

        // 设置查询键相关信息
        if (rule.getQueryKey() != null && !matches.isEmpty()) {
            builder.queryKey(rule.getQueryKey());
            builder.queryValue(extractValue(matches.get(0), rule.getQueryKey()));
        }

        // 设置聚合键相关信息
        if (rule.getAggregationKey() != null && !matches.isEmpty()) {
            builder.aggregationKey(rule.getAggregationKey());
            builder.aggregationValue(extractValue(matches.get(0), rule.getAggregationKey()));
        }

        // 格式化告警内容
        builder.alertSubject(formatAlertSubject(rule, matches));
        builder.alertText(formatAlertText(rule, matches));

        // 设置元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rule_owner", rule.getOwner());
        metadata.put("rule_category", rule.getCategory());
        metadata.put("source_index", rule.getIndex());
        metadata.put("alert_time", Instant.now().toString());
        builder.metadata(metadata);

        // 设置标签
        Map<String, String> tags = new HashMap<>();
        tags.put("rule_type", rule.getType());
        tags.put("priority", rule.getPriority());
        builder.tags(tags);

        // 设置优先级
        builder.priority(AlertPriority.fromString(rule.getPriority()));

        // 设置告警配置
        builder.alertConfig(convertAlertConfig(rule));

        return builder.build();
    }

    /**
     * 格式化告警主题
     */
    private static String formatAlertSubject(Rule rule, List<Map<String, Object>> matches) {
        String subject = rule.getAlertSubject();
        if (subject == null) {
            return String.format("%s Alert", rule.getName());
        }

        // 替换模板变量
        if (!matches.isEmpty()) {
            Map<String, Object> match = matches.get(0);
            for (String arg : rule.getAlertSubjectArgs()) {
                String value = String.valueOf(extractValue(match, arg));
                subject = subject.replace("{" + arg + "}", value);
            }
        }

        return subject;
    }

    /**
     * 格式化告警内容
     */
    private static String formatAlertText(Rule rule, List<Map<String, Object>> matches) {
        String text = rule.getAlertText();
        if (text == null) {
            return String.format("Matched %d events", matches.size());
        }

        // 替换模板变量
        if (!matches.isEmpty()) {
            Map<String, Object> match = matches.get(0);
            for (String arg : rule.getAlertTextArgs()) {
                String value = String.valueOf(extractValue(match, arg));
                text = text.replace("{" + arg + "}", value);
            }
        }

        return text;
    }

    /**
     * 从文档中提取字段值
     */
    private static Object extractValue(Map<String, Object> doc, String field) {
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
     * 转换告警配置
     */
    private static Map<String, Object> convertAlertConfig(Rule rule) {
        Map<String, Object> config = new HashMap<>();

        // 添加邮件配置
        if (rule.getEmailTo() != null) {
            config.put("email_to", rule.getEmailTo());
            config.put("email_subject", rule.getAlertSubject());
        }

        // 添加Slack配置
        if (rule.getSlackWebhookUrl() != null) {
            config.put("slack_webhook_url", rule.getSlackWebhookUrl());
            config.put("slack_channel", rule.getSlackChannel());
            config.put("slack_username", rule.getSlackUsername());
        }

        // 添加Webhook配置
        if (rule.getWebhookUrl() != null) {
            config.put("webhook_url", rule.getWebhookUrl());
            config.put("webhook_headers", rule.getWebhookHeaders());
        }

        return config;
    }

    /**
     * 获取格式化的告警信息
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("alert_id", alertId);
        map.put("create_time", createTime);
        map.put("rule_name", ruleName);
        map.put("rule_type", ruleType);
        map.put("alert_subject", alertSubject);
        map.put("alert_text", alertText);
        map.put("matches", matches);
        map.put("match_count", matchCount);
        map.put("metadata", metadata);
        map.put("tags", tags);
        map.put("priority", priority);
        return map;
    }

    /**
     * 告警优先级枚举
     */
    public enum AlertPriority {
        P1,  // 最高优先级
        P2,
        P3,
        P4,
        P5;  // 最低优先级

        public static AlertPriority fromString(String priority) {
            try {
                return valueOf(priority.toUpperCase());
            } catch (Exception e) {
                return P3;  // 默认中等优先级
            }
        }
    }

    /**
     * 判断是否需要立即处理
     */
    public boolean isUrgent() {
        return priority == AlertPriority.P1 || priority == AlertPriority.P2;
    }

    /**
     * 获取关键标识信息(用于去重)
     */
    public String getDeduplicationKey() {
        return String.format("%s_%s_%s",
                ruleName,
                queryValue != null ? queryValue : "",
                aggregationValue != null ? aggregationValue : "");
    }
}
