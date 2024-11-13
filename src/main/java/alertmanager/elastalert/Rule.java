package alertmanager.elastalert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则配置模型类
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {
    private List<AlertConfig> alerts;
    // 基本配置
    private String name;                    // 规则名称
    private String type;                    // 规则类型(frequency/spike/flatline等)
    private String index;                   // ES索引名称
    private boolean enabled = true;         // 规则是否启用

    // 时间相关配置
    @JsonProperty("timestamp_field")
    private String timestampField = "timestamp";  // 时间戳字段
    private Duration timeframe;             // 时间窗口
    @JsonProperty("run_every")
    private Duration runEvery;             // 执行间隔
    private Duration buffer_time;           // 缓冲时间

    // 查询相关配置
    private List<Map<String, Object>> filter;      // ES查询过滤条件
    @JsonProperty("query_key")
    private String queryKey;                // 查询键字段
    @JsonProperty("compound_query_key")
    private List<String> compoundQueryKey;  // 复合查询键字段
    private List<String> include;           // 需要包含的字段
    private List<String> exclude;           // 需要排除的字段
    @JsonProperty("max_query_size")
    private int maxQuerySize = 10000;       // 最大查询大小

    // 规则特定配置
    @JsonProperty("num_events")
    private Integer numEvents;              // frequency规则的事件数阈值
    @JsonProperty("spike_height")
    private Double spikeHeight;             // spike规则的峰值高度
    @JsonProperty("spike_type")
    private String spikeType;               // spike规则类型(up/down/both)
    @JsonProperty("threshold")
    private Integer threshold;              // flatline规则的阈值

    // 聚合相关配置
    private Duration aggregation;           // 聚合时间窗口
    @JsonProperty("aggregation_key")
    private String aggregationKey;          // 聚合键字段
    @JsonProperty("summary_table_fields")
    private List<String> summaryTableFields; // 汇总表字段

    @JsonProperty("alert_subject")
    private String alertSubject;            // 告警主题模板
    @JsonProperty("alert_subject_args")
    private List<String> alertSubjectArgs;  // 告警主题参数
    @JsonProperty("alert_text")
    private String alertText;               // 告警内容模板
    @JsonProperty("alert_text_args")
    private List<String> alertTextArgs;     // 告警内容参数
    @JsonProperty("alert_text_type")
    private String alertTextType;           // 告警内容类型

    // 抑制和去重配置
    private Duration realert;               // 重复告警间隔
    @JsonProperty("realert_key")
    private String realertKey;              // 重复告警键
    private List<Map<String, Object>> blacklist; // 黑名单
    private List<Map<String, Object>> whitelist; // 白名单

    // Kibana相关配置
    @JsonProperty("use_kibana_dashboard")
    private String kibanaDashboard;         // Kibana仪表板URL
    @JsonProperty("kibana_url")
    private String kibanaUrl;               // Kibana基础URL

    // 增强和处理配置
    @JsonProperty("match_enhancements")
    private List<String> matchEnhancements; // 匹配增强处理
    private boolean exponential_realert;    // 指数级重复告警
    @JsonProperty("generate_kibana_link")
    private boolean generateKibanaLink;     // 是否生成Kibana链接

    // ES集群配置(可选,覆盖全局配置)
    @JsonProperty("es_host")
    private String esHost;
    @JsonProperty("es_port")
    private Integer esPort;
    @JsonProperty("es_username")
    private String esUsername;
    @JsonProperty("es_password")
    private String esPassword;
    @JsonProperty("es_ssl")
    private Boolean esSsl;

    // Slack特定配置
    @JsonProperty("slack_webhook_url")
    private String slackWebhookUrl;
    @JsonProperty("slack_channel")
    private String slackChannel;
    @JsonProperty("dingding_users")
    private List<String> dingdingUsers;
    @JsonProperty("slack_username_override")
    private String slackUsername;
    @JsonProperty("slack_emoji_override")
    private String slackEmoji;
    @JsonProperty("slack_msg_color")
    private String slackMsgColor;

    // 邮件特定配置
    @JsonProperty("email")
    private List<String> emailTo;
    @JsonProperty("email_from_field")
    private String emailFromField;
    @JsonProperty("smtp_host")
    private String smtpHost;
    @JsonProperty("smtp_port")
    private Integer smtpPort;
    @JsonProperty("smtp_auth")
    private Boolean smtpAuth;
    @JsonProperty("smtp_ssl")
    private Boolean smtpSsl;

    // Webhook特定配置
    @JsonProperty("http_post_url")
    private String webhookUrl;
    @JsonProperty("http_post_headers")
    private Map<String, String> webhookHeaders;
    @JsonProperty("http_post_payload")
    private Map<String, Object> webhookPayload;

    // 元数据字段
    private String description;             // 规则描述
    private String owner;                   // 规则所有者
    private String priority;                // 规则优先级
    private String category;                // 规则分类
    private String sourcePath;              // 规则文件路径

    // Getters and Setters
    // ... 每个字段的getter和setter方法

    /**
     * 转换为Map
     */
    public Map<String, Object> toMap() {
        // 将规则配置转换为Map形式
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("index", index);
        // ... 其他字段
        return map;
    }

    /**
     * 验证规则配置
     */
    public void validate() throws ValidationException {
        // 基本验证
        if (name == null || name.isEmpty()) {
            throw new ValidationException("规则名称不能为空");
        }
        if (type == null || type.isEmpty()) {
            throw new ValidationException("规则类型不能为空");
        }
        if (index == null || index.isEmpty()) {
            throw new ValidationException("索引名称不能为空");
        }

        // 根据规则类型进行特定验证
        switch (type) {
            case "frequency":
                validateFrequencyRule();
                break;
            case "spike":
                validateSpikeRule();
                break;
            case "flatline":
                validateFlatlineRule();
                break;
            // ... 其他规则类型的验证
        }

        // 验证告警配置
        if (alerts == null || alerts.isEmpty()) {
            throw new ValidationException("至少需要配置一种告警方式");
        }
    }

    // 特定规则类型的验证方法
    private void validateFrequencyRule() throws ValidationException {
        if (numEvents == null || numEvents <= 0) {
            throw new ValidationException("frequency规则必须设置有效的num_events");
        }
        if (timeframe == null) {
            throw new ValidationException("frequency规则必须设置timeframe");
        }
    }

    private void validateSpikeRule() throws ValidationException {
        if (spikeHeight == null || spikeHeight <= 0) {
            throw new ValidationException("spike规则必须设置有效的spike_height");
        }
        if (spikeType == null || !Arrays.asList("up", "down", "both").contains(spikeType)) {
            throw new ValidationException("spike规则必须设置有效的spike_type(up/down/both)");
        }
    }

    private void validateFlatlineRule() throws ValidationException {
        if (threshold == null || threshold <= 0) {
            throw new ValidationException("flatline规则必须设置有效的threshold");
        }
        if (timeframe == null) {
            throw new ValidationException("flatline规则必须设置timeframe");
        }
    }
}