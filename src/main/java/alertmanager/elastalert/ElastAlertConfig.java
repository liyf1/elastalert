package alertmanager.elastalert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ElastAlertConfig {
    private final Map<String, Object> config;
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private ElastAlertConfig(Map<String, Object> config) {
        this.config = config;
    }

    /**
     * 加载配置文件
     */
    public static ElastAlertConfig load(String configPath) {
        try {
            Path path = Paths.get(configPath);
            Map<String, Object> config = yamlMapper.readValue(
                    new File(path.toAbsolutePath().toString()),
                    Map.class
            );
            return new ElastAlertConfig(config);
        } catch (Exception e) {
            throw new RuntimeException("加载配置文件失败: " + configPath, e);
        }
    }

    /**
     * 获取字符串配置
     */
    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        Object value = getValue(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 获取整数配置
     */
    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Object value = getValue(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 获取布尔配置
     */
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = getValue(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * 获取列表配置
     */
    public List<String> getStringList(String key) {
        Object value = getValue(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return Collections.emptyList();
    }

    /**
     * 获取子配置
     */
    public Map<String, Object> getSubConfig(String key) {
        Object value = getValue(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    /**
     * 获取配置值
     */
    private Object getValue(String key) {
        if (StringUtils.isEmpty(key)) {
            return null;
        }

        String[] parts = key.split("\\.");
        Map<String, Object> current = config;

        for (int i = 0; i < parts.length - 1; i++) {
            Object value = current.get(parts[i]);
            if (!(value instanceof Map)) {
                return null;
            }
            current = (Map<String, Object>) value;
        }

        return current.get(parts[parts.length - 1]);
    }

    /**
     * 验证配置
     */
    public void validate() {
        // 验证必需的配置项
        validateRequired("elasticsearch.host", "Elasticsearch主机未配置");
        validateRequired("elasticsearch.port", "Elasticsearch端口未配置");
        validateRequired("rules.folder", "规则目录未配置");

        // 验证其他配置...
    }

    private void validateRequired(String key, String message) {
        if (getValue(key) == null) {
            throw new IllegalArgumentException(message);
        }
    }
}