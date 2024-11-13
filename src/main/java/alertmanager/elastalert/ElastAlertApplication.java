package alertmanager.elastalert;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * ElastAlert应用主类 - 适配ES 8.8.3版本
 */
public class ElastAlertApplication implements AutoCloseable, RuleChangeListener {
    private static final Logger logger = LoggerFactory.getLogger(ElastAlertApplication.class);

    private final ElastAlertConfig config;
    private final ElasticsearchClient esClient;
    private final RestClient restClient;
    private final RuleLoader ruleLoader;
    private final ESAlertManager alertManager;
    private final ScheduledExecutorService scheduler;
    private final Map<String, RuleRunner> ruleRunners;
    private final ThreadPoolExecutor executorService;
    private volatile boolean running;
    private final Map<String, ScheduledFuture<?>> ruleSchedules = new ConcurrentHashMap<>();

    /**
     * 启动应用
     */
    public void start() {
        if (running) {
            logger.warn("ElastAlert已经在运行");
            return;
        }

        try {
            logger.info("正在启动ElastAlert...");
            running = true;

            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));

            // 加载所有规则
            ruleLoader.loadAllRules();

            // 初始化规则执行器
            initializeRuleRunners();

            // 启动健康检查
            startHealthCheck();

            logger.info("ElastAlert启动成功");

        } catch (Exception e) {
            running = false;
            logger.error("启动ElastAlert失败", e);
            throw new RuntimeException("启动失败", e);
        }
    }

    /**
     * 启动健康检查
     */
    private void startHealthCheck() {
        scheduler.scheduleAtFixedRate(
                this::performHealthCheck,
                0,
                config.getInt("health.check.interval", 60),
                TimeUnit.SECONDS
        );
    }

    /**
     * 初始化规则执行器
     */
    private void initializeRuleRunners() throws IOException {
        for (Rule rule : ruleLoader.getAllRules()) {
            if (rule.isEnabled()) {
                scheduleRule(rule);
            }
        }
    }

    /**
     * 调度规则执行
     */
    private void scheduleRule(Rule rule) {
        try {
            RuleRunner runner = new RuleRunner(rule, esClient, alertManager);
            ruleRunners.put(rule.getName(), runner);

            // 计算执行间隔
            long intervalSeconds = rule.getRunEvery().getSeconds();

            // 调度定期执行
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> executeRule(runner),
                    randomInitialDelay(),
                    intervalSeconds,
                    TimeUnit.SECONDS
            );

            // 保存调度任务
            ruleSchedules.put(rule.getName(), future);

            logger.info("规则已调度: {}, 间隔: {}秒", rule.getName(), intervalSeconds);

        } catch (Exception e) {
            logger.error("规则调度失败: {}", rule.getName(), e);
        }
    }

    /**
     * 执行规则
     */
    private void executeRule(RuleRunner runner) {
        if (!running) {
            return;
        }

        executorService.submit(() -> {
            try {
                runner.execute();
            } catch (Exception e) {
                logger.error("规则执行失败", e);
            }
        });
    }

    /**
     * 构造函数
     */
    public ElastAlertApplication(String configPath) {
        try {
            // 加载配置
            this.config = ElastAlertConfig.load(configPath);

            // 初始化ES客户端
            RestClient restClientTemp = createRestClient();
            this.restClient = restClientTemp;
            this.esClient = createEsClient(restClientTemp);

            // 初始化线程池
            this.scheduler = Executors.newScheduledThreadPool(
                    config.getInt("threadpool.scheduler.size", 5),
                    new ThreadFactoryBuilder()
                            .setNameFormat("elastalert-scheduler-%d")
                            .build()
            );

            this.executorService = new ThreadPoolExecutor(
                    config.getInt("threadpool.core.size", 10),
                    config.getInt("threadpool.max.size", 20),
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(1000),
                    new ThreadFactoryBuilder()
                            .setNameFormat("elastalert-worker-%d")
                            .build(),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            // 初始化规则加载器
            Path rulesPath = Paths.get(config.getString("rules.folder"));
            this.ruleLoader = new RuleLoader(rulesPath, scheduler);

            // 初始化告警管理器
            this.alertManager = new ESAlertManager(
                    esClient,
                    config.getString("alert.index", "elastalert_alerts")
            );

            this.ruleRunners = new ConcurrentHashMap<>();
            this.running = false;
            this.ruleLoader.addChangeListener(this);

        } catch (Exception e) {
            logger.error("初始化ElastAlert失败", e);
            throw new RuntimeException("初始化失败", e);
        }
    }

    @Override
    public void onRuleAdded(Rule rule) {
        if (rule.isEnabled()) {
            try {
                logger.info("添加新规则: {}", rule.getName());
                scheduleRule(rule);
            } catch (Exception e) {
                logger.error("添加规则失败: {}", rule.getName(), e);
            }
        }
    }

    @Override
    public void onRuleUpdated(Rule rule) {
        try {
            logger.info("更新规则: {}", rule.getName());

            // 停止现有的规则执行器
            RuleRunner existingRunner = ruleRunners.get(rule.getName());
            if (existingRunner != null) {
                stopRule(rule.getName());
            }

            // 如果规则启用，重新调度
            if (rule.isEnabled()) {
                scheduleRule(rule);
            }
        } catch (Exception e) {
            logger.error("更新规则失败: {}", rule.getName(), e);
        }
    }

    @Override
    public void onRuleDeleted(Rule rule) {
        try {
            logger.info("删除规则: {}", rule.getName());
            stopRule(rule.getName());
        } catch (Exception e) {
            logger.error("删除规则失败: {}", rule.getName(), e);
        }
    }

    @Override
    public void onRuleLoadError(String rulePath, Exception error) {
        logger.error("加载规则失败: {}", rulePath, error);
    }

    /**
     * 停止规则执行
     */
    private void stopRule(String ruleName) {
        RuleRunner runner = ruleRunners.remove(ruleName);
        if (runner != null) {
            // 取消定时任务
            ScheduledFuture<?> future = ruleSchedules.remove(ruleName);
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    /**
     * 创建ES REST客户端
     */
    private RestClient createRestClient() {
        String host = config.getString("elasticsearch.host");
        int port = config.getInt("elasticsearch.port");
        String scheme = config.getString("elasticsearch.scheme", "http");
        boolean sslEnabled = config.getBoolean("elasticsearch.ssl", true);
        String userName = config.getString("elasticsearch.username");
        String password = config.getString("elasticsearch.password");
        int connectTimeOut = config.getInt("elasticsearch.timeout", 30) * 1000;
        int socketTimeOut = config.getInt("elasticsearch.timeout", 30) * 1000;
        int connectionRequestTimeOut = config.getInt("elasticsearch.timeout", 30) * 1000;
        int maxConnectNum = config.getInt("threadpool.core.size", 10);
        int maxConnectPerRoute = config.getInt("threadpool.max.size", 20);

        // 构建连接对象
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(host, port, scheme)
        );

        // 设置用户名、密码
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(userName, password)
        );

        // 配置连接参数
        builder.setRequestConfigCallback(requestConfigBuilder ->
                requestConfigBuilder
                        .setConnectTimeout(connectTimeOut)
                        .setSocketTimeout(socketTimeOut)
                        .setConnectionRequestTimeout(connectionRequestTimeOut)
        );

        // 配置HTTP客户端
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            if (sslEnabled) {
                try {
                    SSLContext sslContext = SSLContextBuilder.create()
                            .loadTrustMaterial((chain, authType) -> true)
                            .build();
                    httpClientBuilder.setSSLContext(sslContext);
                    httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create SSLContext", e);
                }
            }
            return httpClientBuilder
                    .setMaxConnTotal(maxConnectNum)
                    .setMaxConnPerRoute(maxConnectPerRoute)
                    .setDefaultCredentialsProvider(credentialsProvider);
        });

        return builder.build();
    }

    /**
     * 创建ES高级客户端
     */
    private ElasticsearchClient createEsClient(RestClient restClient) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 创建传输层
        ElasticsearchTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper(mapper)
        );

        // 创建API客户端
        return new ElasticsearchClient(transport);
    }

    /**
     * 执行健康检查
     */
    private void performHealthCheck() {
        try {
            checkESVersion();
            createHealthIndex();
            HealthStatus health = new HealthStatus();
            List<String> issues = new ArrayList<>();

            // 检查ES连接
            boolean esHealthy = checkEsConnection();
            health.setEsHealthy(esHealthy);
            if (!esHealthy) {
                issues.add("Elasticsearch连接异常");
            }

            // 更新整体健康状态
            updateHealthStatus(health, issues);

            // 记录健康检查结果
            logHealthStatus(health);

        } catch (Exception e) {
            logger.error("健康检查失败", e);
        }
    }

    /**
     * 更新健康状态
     */
    private void updateHealthStatus(HealthStatus health, List<String> issues) {
        health.setIssues(issues);
        // 确定整体状态
        if (issues.isEmpty()) {
            health.setStatus("HEALTHY");
        } else if (!health.isEsHealthy()) {
            health.setStatus("UNHEALTHY");
        } else {
            health.setStatus("DEGRADED");
        }

        // 发送健康状态通知
        if (!"HEALTHY".equals(health.getStatus())) {
            sendHealthAlert(health);
        }

        // 记录到ES
        saveHealthStatus(health);
    }

    /**
     * 保存健康状态到ES
     */
    private void saveHealthStatus(HealthStatus health) {
        try {
            String healthIndex = config.getString("health.index", ".elastalert_health");
            IndexRequest<Object> indexRequest = new IndexRequest.Builder<>()
                    .index(healthIndex)
                    .document(health)
                    .build();
            // 使用新版API创建索引请求
            esClient.index(indexRequest);

        } catch (Exception e) {
            logger.error("保存健康状态失败", e);
        }
    }
    /**
     * 检查ES版本
     */
    private void checkESVersion() {
        try {
            InfoResponse response = esClient.info();
            logger.info("Connected to Elasticsearch version: {}", response.version().number());
        } catch (IOException e) {
            logger.error("Failed to get Elasticsearch version", e);
        }
    }

    /**
     * 检查ES连接
     */
    private boolean checkEsConnection() {
        try {
            // 检查集群健康状态
            HealthResponse healthResponse = esClient.cluster().health();
            if (healthResponse.status().equals("red")) {
                logger.warn("Elasticsearch集群状态为RED");
                return false;
            }

            // 检查查询能力
            String testIndex = config.getString("health.index", "elastalert_health");
            SearchResponse<Map> searchResponse = esClient.search(s -> s
                            .index(testIndex)
                            .size(1),
                    Map.class
            );

            return searchResponse.took() >= 0;

        } catch (Exception e) {
            logger.error("ES连接检查失败", e);
            return false;
        }
    }

    /**
     * 创建健康检查索引
     */
    private void createHealthIndex() {
        try {
            String healthIndex = config.getString("health.index", ".elastalert_health");

            // 检查索引是否存在
            boolean indexExists = esClient.indices().exists(e -> e
                    .index(healthIndex)
            ).value();

            if (!indexExists) {
                // 创建索引
                CreateIndexResponse response = esClient.indices().create(c -> c
                        .index(healthIndex)
                        .mappings(m -> m
                                .properties("esHealthy", p -> p.boolean_(b -> b))
                                .properties("status", p -> p.keyword(k -> k))
                                .properties("lastCheckTime", p -> p
                                        .date(d -> d
                                                .format("epoch_millis||strict_date_optional_time")
                                        )
                                )
                                .properties("ruleHealth", p -> p.object(o -> o))
                                .properties("issues", p -> p.keyword(k -> k))
                        )
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("1")
                        )
                );

                if (response.acknowledged()) {
                    logger.info("Health index created successfully: {}", healthIndex);
                } else {
                    logger.warn("Failed to create health index: {}", healthIndex);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to create health index", e);
        }
    }

    /**
     * 发送健康状态告警
     */
    private void sendHealthAlert(HealthStatus health) {
        if (!config.getBoolean("health.alerts.enabled", true)) {
            return;
        }

        try {
            AlertContext context = AlertContext.builder()
                    .alertId("health_check_" + Instant.now().toEpochMilli())
                    .alertSubject("ElastAlert健康检查告警")
                    .alertText(formatHealthAlertText(health))
                    .priority(AlertContext.AlertPriority.P1)
                    .metadata(Collections.singletonMap("health_status", health))
                    .build();

            alertManager.handleAlert(context, null);
        } catch (Exception e) {
            logger.error("发送健康状态告警失败", e);
        }
    }

    /**
     * 格式化健康检查告警文本
     */
    private String formatHealthAlertText(HealthStatus health) {
        StringBuilder text = new StringBuilder();
        text.append("ElastAlert健康检查发现以下问题：\n\n");

        for (String issue : health.getIssues()) {
            text.append("- ").append(issue).append("\n");
        }

        text.append("\n状态详情：\n");
        text.append("- ES连接状态：").append(health.isEsHealthy() ? "正常" : "异常").append("\n");
        text.append("- 检查时间：").append(health.getLastCheckTime()).append("\n");
        text.append("- 整体状态：").append(health.getStatus()).append("\n\n");

        text.append("规则状态：\n");
        for (RuleHealthStatus ruleStatus : health.getRuleHealth().values()) {
            text.append("- ").append(ruleStatus.getRuleName()).append(": ")
                    .append(ruleStatus.getStatus());
            if (ruleStatus.getLastError() != null) {
                text.append(" (").append(ruleStatus.getLastError()).append(")");
            }
            text.append("\n");
        }

        return text.toString();
    }

    /**
     * 记录健康检查日志
     */
    private void logHealthStatus(HealthStatus health) {
        if ("HEALTHY".equals(health.getStatus())) {
            logger.info("健康检查通过，系统运行正常");
        } else {
            logger.warn("健康检查发现问题：{}，状态：{}",
                    String.join(", ", health.getIssues()),
                    health.getStatus());
            sendHealthAlert(health);
        }
    }

    /**
     * 关闭应用
     */
    /**
     * 关闭应用
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }

        try {
            logger.info("正在关闭ElastAlert...");
            running = false;

            // 移除规则变更监听器
            ruleLoader.removeChangeListener(this);

            // 取消所有规则调度
            ruleSchedules.values().forEach(future -> future.cancel(false));
            ruleSchedules.clear();

            // 停止接收新的任务
            executorService.shutdown();
            scheduler.shutdown();

            // 等待现有任务完成
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }

            // 关闭规则加载器
            ruleLoader.close();

            // 关闭告警管理器
            alertManager.shutdown();

            // 关闭ES客户端
            if (esClient != null) {
                restClient.close();
            }

            logger.info("ElastAlert已关闭");

        } catch (Exception e) {
            logger.error("关闭ElastAlert时发生错误", e);
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }
    /**
     * 生成随机初始延迟
     */
    private long randomInitialDelay() {
        return ThreadLocalRandom.current().nextLong(15);
    }

    /**
     * 应用程序入口
     */
    public static void main(String[] args) {

        try (ElastAlertApplication app = new ElastAlertApplication("src/main/resources/elastconfig.yml")) {
            app.start();

            // 保持应用运行
            while (app.isRunning()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            logger.error("应用运行错误", e);
            System.exit(1);
        }
    }

}
