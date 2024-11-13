package alertmanager.elastalert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 规则加载器 - 负责加载和管理规则配置
 */
public class RuleLoader implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RuleLoader.class);

    private final Path rulesDirectory;
    private final ObjectMapper yamlMapper;
    private final Map<String, Rule> loadedRules;
    private final Map<String, String> ruleHashes;
    private final ScheduledExecutorService scheduler;
    private final List<RuleChangeListener> changeListeners;
    private final WatchService watchService;
    private volatile boolean watching = false;
    private final Map<String, FileState> fileStates = new ConcurrentHashMap<>();


    private Thread watchThread;

    // 告警类型注册表
    private final Map<String, AlertConfigFactory> alertFactories;
    private ScheduledFuture<?> watchTask;

    /**
     * 构造函数
     */
    public RuleLoader(Path rulesDirectory, ScheduledExecutorService scheduler) throws IOException {
        this.rulesDirectory = rulesDirectory;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.loadedRules = new ConcurrentHashMap<>();
        this.ruleHashes = new ConcurrentHashMap<>();
        this.scheduler = scheduler;
        this.changeListeners = new CopyOnWriteArrayList<>();
        this.alertFactories = new ConcurrentHashMap<>();
        this.watchService = FileSystems.getDefault().newWatchService();
        this.rulesDirectory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

        // 注册默认的告警类型工厂
        registerDefaultAlertFactories();
        startWatching();
    }

    /**
     * 文件状态类
     */
    private static class FileState {
        private final String path;
        private String hash;
        private long lastModified;
        private long size;

        public FileState(String path, String hash, long lastModified, long size) {
            this.path = path;
            this.hash = hash;
            this.lastModified = lastModified;
            this.size = size;
        }

        public boolean hasChanged(FileState other) {
            return !this.hash.equals(other.hash) ||
                    this.lastModified != other.lastModified ||
                    this.size != other.size;
        }
    }

    /**
     * 注册默认的告警类型
     */
    private void registerDefaultAlertFactories() {
        // 注册Webhook告警
        registerAlertFactory("webhook", config -> new WebhookAlert(config));
        registerAlertFactory("dingding", config -> new DingdingAlert(config));

//        // 注册Email告警
//        registerAlertFactory("email", config -> new EmailAlert(config));
//
//        // 注册Slack告警
//        registerAlertFactory("slack", config -> new SlackAlert(config));
//
//        // 注册自定义HTTP告警
//        registerAlertFactory("http", config -> new HttpAlert(config));
//
//        // 注册控制台告警(用于测试)
//        registerAlertFactory("console", config -> new ConsoleAlert());
    }

    /**
     * 注册告警工厂
     */
    public void registerAlertFactory(String type, AlertConfigFactory factory) {
        alertFactories.put(type.toLowerCase(), factory);
        logger.info("注册告警类型: {}", type);
    }

    /**
     * 加载所有规则
     */
    public List<Rule> loadAllRules() throws IOException {
        logger.info("开始加载规则目录: {}", rulesDirectory);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDirectory, "*.{yaml,yml}")) {
            for (Path path : stream) {
                try {
                    Rule rule = loadRule(path);
                    assert rule != null;
                    loadedRules.put(rule.getName(), rule);
                } catch (Exception e) {
                    logger.error("加载规则文件失败: {}", path, e);
                }
            }
        }

        return loadedRules.values().stream().sorted(Comparator.comparing(Rule::getName)).collect(Collectors.toList());
    }

    public List<Rule> getAllRules() {
        return new ArrayList<>(loadedRules.values());
    }

    /**
     * 加载单个规则文件
     */
    private Rule loadRule(Path path) throws IOException, ValidationException {
        logger.debug("加载规则文件: {}", path);

        // 计算文件hash
        String currentHash = calculateFileHash(path);
        String existingHash = ruleHashes.get(path.toString());

        // 如果文件没有变化，跳过加载
        if (currentHash.equals(existingHash)) {
            return null;
        }

        // 读取YAML文件
        Map<String, Object> ruleMap = yamlMapper.readValue(path.toFile(), Map.class);

        // 基本验证
        validateRuleConfig(ruleMap, path);

        // 构建规则对象
        Rule rule = buildRule(ruleMap, path);

        // 加载告警配置
        List<AlertConfig> alerts = loadAlertConfigs(ruleMap);
        rule.setAlerts(alerts);

        // 验证完整的规则配置
        rule.validate();

        // 更新规则和hash
        String ruleName = rule.getName();

        logger.info("规则加载成功: {}", ruleName);
        return rule;
    }

    /**
     * 加载告警配置
     */
    private List<AlertConfig> loadAlertConfigs(Map<String, Object> ruleMap) throws ConfigurationException {
        List<AlertConfig> alerts = new ArrayList<>();

        // 获取告警配置列表
        List<Map<String, Object>> alertConfigs = (List<Map<String, Object>>) ruleMap.get("alert");
        if (alertConfigs == null || alertConfigs.isEmpty()) {
            throw new ConfigurationException("规则必须配置至少一种告警方式");
        }

        // 加载每个告警配置
        for (Map<String, Object> config : alertConfigs) {
            String type = (String) config.get("type");
            if (type == null) {
                throw new ConfigurationException("告警配置必须指定type");
            }

            // 查找对应的告警工厂
            AlertConfigFactory factory = alertFactories.get(type.toLowerCase());
            if (factory == null) {
                throw new ConfigurationException("不支持的告警类型: " + type);
            }

            try {
                // 创建告警配置
                AlertConfig alert = factory.create(config);
                alerts.add(alert);
            } catch (Exception e) {
                throw new ConfigurationException("创建告警配置失败: " + type, e);
            }
        }

        return alerts;
    }

    /**
     * 验证规则配置
     */
    private void validateRuleConfig(Map<String, Object> config, Path path) throws ConfigurationException {
        // 检查必需字段
        List<String> requiredFields = Arrays.asList("name", "type", "index", "alert");
        for (String field : requiredFields) {
            if (!config.containsKey(field)) {
                throw new ConfigurationException(
                        String.format("规则配置缺少必需字段 '%s': %s", field, path));
            }
        }

        // 检查规则名称唯一性
        String name = (String) config.get("name");
        for (Rule rule : loadedRules.values()) {
            if (rule.getName().equals(name) && !rule.getSourcePath().equals(path.toString())) {
                throw new ConfigurationException(
                        String.format("规则名称 '%s' 已存在于文件: %s", name, rule.getSourcePath()));
            }
        }
    }

    /**
     * 构建规则对象
     */
    private Rule buildRule(Map<String, Object> config, Path path) {
        Rule rule = new Rule();

        // 设置基本属性
        rule.setName((String) config.get("name"));
        rule.setType((String) config.get("type"));
        rule.setIndex((String) config.get("index"));
        rule.setSourcePath(path.toString());

        // 设置可选属性
        rule.setEnabled((Boolean) config.getOrDefault("enabled", true));
        rule.setDescription((String) config.get("description"));
        rule.setOwner((String) config.get("owner"));
        rule.setPriority((String) config.getOrDefault("priority", "P3"));
        rule.setTimestampField((String) config.get("timestamp_field"));
        // 设置查询相关配置
        rule.setFilter((List<Map<String, Object>>) config.get("filter"));
        rule.setQueryKey((String) config.get("query_key"));
        rule.setAggregationKey((String) config.get("aggregation_key"));

        // 设置时间相关配置
        if (config.containsKey("timeframe")) {
            rule.setTimeframe(parseDuration(config.get("timeframe")));
        }
        if (config.containsKey("realert")) {
            rule.setRealert(parseDuration(config.get("realert")));
        }

        if (config.containsKey("include")){
            rule.setInclude((List<String>) config.get("include"));
        }

        if (config.containsKey("alert_text")){
            rule.setAlertText((String) config.get("alert_text"));
        }
        if (config.containsKey("alert_text_args")){
            rule.setAlertTextArgs((List<String>) config.get("alert_text_args"));
        }

        rule.setRunEvery(parseDurationMap((Map<String, Object>) config.get("run_every")));

        // 设置规则特定配置
        setRuleTypeSpecificConfig(rule, config);

        return rule;
    }

    /**
     * 设置规则类型特定的配置
     */
    private void setRuleTypeSpecificConfig(Rule rule, Map<String, Object> config) {
        switch (rule.getType()) {
            case "frequency":
                rule.setNumEvents((Integer) config.get("num_events"));
                break;

            case "spike":
                rule.setSpikeHeight((Double) config.get("spike_height"));
                rule.setSpikeType((String) config.get("spike_type"));
                break;

            case "flatline":
                rule.setThreshold((Integer) config.get("threshold"));
                break;
        }
    }

    /**
     * 解析时间周期配置
     */
    private Duration parseDuration(Object value) {
        if (value instanceof String) {
            // 解析字符串格式，如 "5m", "1h", "1d"
            return parseDurationString((String) value);
        } else if (value instanceof Map) {
            // 解析映射格式，如 {"minutes": 5}
            return parseDurationMap((Map<String, Object>) value);
        }
        throw new ConfigurationException("无效的时间周期格式: " + value);
    }

    /**
     * 解析时间周期字符串
     */
    private Duration parseDurationString(String value) {
        String number = value.replaceAll("[^0-9]", "");
        String unit = value.replaceAll("[0-9]", "");

        long amount = Long.parseLong(number);

        switch (unit.toLowerCase()) {
            case "s":
                return Duration.ofSeconds(amount);
            case "m":
                return Duration.ofMinutes(amount);
            case "h":
                return Duration.ofHours(amount);
            case "d":
                return Duration.ofDays(amount);
            default:
                throw new ConfigurationException("无效的时间单位: " + unit);
        }
    }

    /**
     * 解析时间周期映射
     */
    private Duration parseDurationMap(Map<String, Object> map) {
        Duration duration = Duration.ZERO;

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String unit = entry.getKey().toLowerCase();
            long amount = ((Number) entry.getValue()).longValue();

            switch (unit) {
                case "seconds":
                    duration = duration.plusSeconds(amount);
                    break;
                case "minutes":
                    duration = duration.plusMinutes(amount);
                    break;
                case "hours":
                    duration = duration.plusHours(amount);
                    break;
                case "days":
                    duration = duration.plusDays(amount);
                    break;
                default:
                    throw new ConfigurationException("无效的时间单位: " + unit);
            }
        }

        return duration;
    }

    /**
     * 检查是否为YAML文件
     */
    private boolean isYamlFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
    }


    /**
     * 获取异常堆栈信息
     */
    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }




    /**
     * 告警配置工厂接口
     */
    @FunctionalInterface
    public interface AlertConfigFactory {
        AlertConfig create(Map<String, Object> config) throws ConfigurationException;
    }

    /**
     * 配置异常
     */
    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 初始化文件状态
     */
    private void initializeFileStates() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDirectory, "*.{yaml,yml}")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    FileState state = createFileState(path);
                    fileStates.put(path.toString(), state);
                }
            }
        }
    }

    /**
     * 创建文件状态
     */
    private FileState createFileState(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        String hash = calculateFileHash(path);
        return new FileState(
                path.toString(),
                hash,
                attrs.lastModifiedTime().toMillis(),
                attrs.size()
        );
    }

    /**
     * 计算文件hash
     */
    private String calculateFileHash(Path path) throws IOException {
        byte[] content = Files.readAllBytes(path);
        return DigestUtils.md5Hex(content);
    }

    /**
     * 启动文件监听
     */
    private void startWatching() {
        if (watching) {
            return;
        }

        try {
            initializeFileStates();
        } catch (IOException e) {
            logger.error("初始化文件状态失败", e);
        }

        watching = true;
        watchThread = new Thread(() -> {
            try {
                while (watching) {
                    WatchKey key = watchService.take();
                    boolean needCheck = false;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        // 处理overflow事件
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            needCheck = true;
                            continue;
                        }

                        Path filename = (Path) event.context();
                        if (!isYamlFile(filename)) {
                            continue;
                        }

                        Path fullPath = rulesDirectory.resolve(filename);
                        String pathStr = fullPath.toString();

                        try {
                            if (kind == ENTRY_CREATE) {
                                handleFileCreated(fullPath);
                            } else if (kind == ENTRY_MODIFY) {
                                handleFileModified(fullPath);
                            } else if (kind == ENTRY_DELETE) {
                                handleFileDeleted(fullPath);
                            }
                        } catch (IOException e) {
                            logger.error("处理文件变更失败: " + pathStr, e);
                        }
                    }

                    // 如果发生overflow,执行完整检查
                    if (needCheck) {
                        checkAllFiles();
                    }

                    if (!key.reset()) {
                        logger.error("规则目录不再可访问");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("文件监听异常", e);
            }
        }, "rule-watcher");

        watchThread.setDaemon(true);
        watchThread.start();
        logger.info("规则文件监听已启动");

        // 定期执行完整性检查
        scheduler.scheduleWithFixedDelay(
                this::checkAllFiles,
                1,
                5,
                TimeUnit.MINUTES
        );
    }

    /**
     * 处理文件创建
     */
    private void handleFileCreated(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return;
        }

        FileState newState = createFileState(path);
        fileStates.put(path.toString(), newState);

        logger.info("检测到新规则文件: {}", path);
        loadAndNotifyRule(path);
    }

    /**
     * 处理文件修改
     */
    private void handleFileModified(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return;
        }

        String pathStr = path.toString();
        FileState oldState = fileStates.get(pathStr);
        FileState newState = createFileState(path);

        // 检查文件是否真的发生变化
        if (oldState == null || oldState.hasChanged(newState)) {
            fileStates.put(pathStr, newState);
            logger.info("检测到规则文件变更: {}", path);
            loadAndNotifyRule(path);
        }
    }

    /**
     * 处理文件删除
     */
    private void handleFileDeleted(Path path) {
        String pathStr = path.toString();
        FileState state = fileStates.remove(pathStr);

        if (state != null) {
            logger.info("检测到规则文件删除: {}", path);

            // 添加延迟检查，避免因为文件覆盖操作导致的监听顺序问题
            scheduler.schedule(() -> {
                try {
                    // 检查文件是否真的不存在了
                    if (Files.exists(path)) {
                        logger.info("检测到文件仍然存在，可能是覆盖操作，重新加载规则: {}", path);

                        // 重新创建文件状态
                        FileState newState = createFileState(path);
                        fileStates.put(pathStr, newState);

                        // 重新加载规则
                        loadAndNotifyRule(path);
                    } else {
                        // 文件确实被删除了，执行删除通知
                        notifyRuleDeleted(pathStr);
                    }
                } catch (IOException e) {
                    logger.error("处理文件删除后检查失败: " + path, e);
                    // 如果检查失败，为了安全起见，还是执行删除通知
                    notifyRuleDeleted(pathStr);
                }
            }, 1, TimeUnit.SECONDS); // 延迟1秒检查
        }
    }

    /**
     * 检查所有文件
     */
    private void checkAllFiles() {
        try {
            // 获取当前所有规则文件
            Set<String> currentFiles = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDirectory, "*.{yaml,yml}")) {
                for (Path path : stream) {
                    if (Files.isRegularFile(path)) {
                        currentFiles.add(path.toString());
                        handleFileModified(path);
                    }
                }
            }

            // 检查已删除的文件
            Set<String> deletedFiles = new HashSet<>(fileStates.keySet());
            deletedFiles.removeAll(currentFiles);

            for (String deletedFile : deletedFiles) {
                handleFileDeleted(Paths.get(deletedFile));
            }

        } catch (IOException e) {
            logger.error("检查规则文件失败", e);
        }
    }

    /**
     * 加载并通知规则变更
     */
    private void loadAndNotifyRule(Path path) {
        try {
            Rule rule = loadRule(path);

            Rule existingRule = loadedRules.get(rule.getName());

            if (existingRule != null) {
                // 检查规则是否真的发生变化
                if (!rule.equals(existingRule)) {
                    loadedRules.put(rule.getName(), rule);
                    notifyRuleUpdated(rule);
                }
            } else {
                loadedRules.put(rule.getName(), rule);
                notifyRuleAdded(rule);
            }
        } catch (Exception e) {
            logger.error("加载规则失败: " + path, e);
        }
    }

    /**
     * 通知规则添加
     */
    private void notifyRuleAdded(Rule rule) {
        for (RuleChangeListener listener : changeListeners) {
            try {
                listener.onRuleAdded(rule);
            } catch (Exception e) {
                logger.error("通知规则添加失败: " + rule.getName(), e);
            }
        }
    }

    /**
     * 通知规则更新
     */
    private void notifyRuleUpdated(Rule rule) {
        for (RuleChangeListener listener : changeListeners) {
            try {
                listener.onRuleUpdated(rule);
            } catch (Exception e) {
                logger.error("通知规则更新失败: " + rule.getName(), e);
            }
        }
    }

    /**
     * 通知规则删除
     */
    private void notifyRuleDeleted(String path) {
        Rule deletedRule = loadedRules.values().stream()
                .filter(r -> r.getSourcePath().equals(path))
                .findFirst()
                .orElse(null);

        if (deletedRule != null) {
            String ruleName = deletedRule.getName();
            loadedRules.remove(ruleName);

            for (RuleChangeListener listener : changeListeners) {
                try {
                    listener.onRuleDeleted(deletedRule);
                } catch (Exception e) {
                    logger.error("通知规则删除失败: " + ruleName, e);
                }
            }
        }
    }

    /**
     * 添加规则变更监听器
     */
    public void addChangeListener(RuleChangeListener listener) {
        changeListeners.add(listener);
    }

    /**
     * 移除规则变更监听器
     */
    public void removeChangeListener(RuleChangeListener listener) {
        changeListeners.remove(listener);
    }

    @Override
    public void close() {
        watching = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        try {
            watchService.close();
        } catch (IOException e) {
            logger.error("关闭文件监听服务失败", e);
        }
    }
}