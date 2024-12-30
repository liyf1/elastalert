package alertmanager.elastalert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Webhook告警实现
 */
/**
 * Webhook告警配置和实现
 */
public class WebhookAlert extends AlertConfig {
    private static final Logger logger = LoggerFactory.getLogger(WebhookAlert.class);

    private final String webhookUrl;
    private final Map<String, String> headers;
    private final String method;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AlertContentFormatter contentFormatter;

    /**
     * 构造函数
     */
    public WebhookAlert(Map<String, Object> config) {
        super(AlertType.WEBHOOK, (List<String>) config.get("users"));

        // 初始化基本配置
        this.webhookUrl = (String) config.get("webhook_url");
        this.method = (String) config.getOrDefault("method", "POST");

        // 初始化请求头
        this.headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "ElastAlert/1.0");

        // 添加自定义请求头
        Map<String, String> customHeaders = (Map<String, String>) config.get("headers");
        if (customHeaders != null) {
            headers.putAll(customHeaders);
        }

        // 初始化HTTP客户端
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        this.objectMapper = new ObjectMapper();
        this.contentFormatter = new AlertContentFormatter();

        // 验证配置
        validate();
    }

    /**
     * 发送告警
     */
    @Override
    public void sendAlert(Rule rule,AlertContext context) throws AlertException {
        try {
            // 构建告警内容
            String content = buildAlertContent(context);

            // 构建HTTP请求
            HttpRequest request = buildHttpRequest(rule.getWebhookUrl(),content);

            // 发送请求
            HttpResponse<String> response = sendRequest(request);

            // 处理响应
            handleResponse(response);

        } catch (Exception e) {
            throw new AlertException("发送Webhook告警失败", e);
        }
    }

    /**
     * 构建告警内容
     */
    private String buildAlertContent(AlertContext context) throws JsonProcessingException {
        Map<String, Object> alertData = new HashMap<>();

        // 基本信息
        alertData.put("id", context.getAlertId());
        alertData.put("timestamp", context.getCreateTime().toString());
        alertData.put("rule_name", context.getRuleName());

        // 告警内容
        alertData.put("subject", context.getAlertSubject());
        alertData.put("text", context.getAlertText());

        // 匹配信息
        alertData.put("matches", context.getMatches());
        alertData.put("match_count", context.getMatchCount());

        // 附加元数据
        if (context.getMetadata() != null) {
            alertData.put("metadata", context.getMetadata());
        }

        // 转换为JSON
        return objectMapper.writeValueAsString(alertData);
    }

    /**
     * 构建HTTP请求
     */
    private HttpRequest buildHttpRequest(String webhookUrl,String content) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10));

        // 添加请求头
        headers.forEach(builder::header);

        // 设置请求方法和内容
        switch (method.toUpperCase()) {
            case "POST":
                builder.POST(HttpRequest.BodyPublishers.ofString(content));
                break;
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.ofString(content));
                break;
            default:
                throw new IllegalArgumentException("不支持的HTTP方法: " + method);
        }

        return builder.build();
    }

    /**
     * 发送请求
     */
    private HttpResponse<String> sendRequest(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        logger.info("Webhook响应: status={}, body={}",
                response.statusCode(), response.body());

        return response;
    }

    /**
     * 处理响应
     */
    private void handleResponse(HttpResponse<String> response) throws AlertException {
        int statusCode = response.statusCode();

        // 检查响应状态
        if (statusCode < 200 || statusCode >= 300) {
            throw new AlertException(String.format(
                    "Webhook请求失败: status=%d, body=%s",
                    statusCode, response.body()));
        }
    }

    /**
     * 验证配置
     */
    private void validate() {
        if (StringUtils.isBlank(webhookUrl)) {
            throw new IllegalArgumentException("Webhook URL不能为空");
        }

        try {
            new URL(webhookUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("无效的Webhook URL: " + webhookUrl);
        }

        if (!Arrays.asList("POST", "PUT").contains(method.toUpperCase())) {
            throw new IllegalArgumentException("不支持的HTTP方法: " + method);
        }
    }

    /**
     * 告警内容格式化工具类
     */
    private static class AlertContentFormatter {

        /**
         * 格式化告警内容
         */
        public Map<String, Object> format(AlertContext context) {
            Map<String, Object> formatted = new HashMap<>();

            // 格式化基本信息
            formatted.put("alert_info", formatAlertInfo(context));

            // 格式化匹配详情
            formatted.put("match_details", formatMatchDetails(context));

            // 格式化元数据
            if (context.getMetadata() != null) {
                formatted.put("metadata", formatMetadata(context.getMetadata()));
            }

            return formatted;
        }

        private Map<String, Object> formatAlertInfo(AlertContext context) {
            Map<String, Object> info = new HashMap<>();
            info.put("id", context.getAlertId());
            info.put("timestamp", context.getCreateTime());
            info.put("rule_name", context.getRuleName());
            info.put("subject", context.getAlertSubject());
            info.put("text", context.getAlertText());
            return info;
        }

        private List<Map<String, Object>> formatMatchDetails(AlertContext context) {
            List<Map<String, Object>> details = new ArrayList<>();

            for (Map<String, Object> match : context.getMatches()) {
                Map<String, Object> formatted = new HashMap<>();

                // 提取关键字段
                formatted.put("timestamp", match.get("@timestamp"));
                formatted.put("source", match.get("source"));

                // 如果有查询键，添加查询键值
                if (context.getQueryKey() != null) {
                    formatted.put("query_key", match.get(context.getQueryKey()));
                }

                details.add(formatted);
            }

            return details;
        }

        private Map<String, Object> formatMetadata(Map<String, Object> metadata) {
            Map<String, Object> formatted = new HashMap<>();

            // 移除敏感信息
            metadata.forEach((key, value) -> {
                if (!key.contains("password") && !key.contains("secret")) {
                    formatted.put(key, value);
                }
            });

            return formatted;
        }
    }
}
