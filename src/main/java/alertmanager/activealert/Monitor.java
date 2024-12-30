package alertmanager.activealert;

import alertmanager.config.ConfigFilePathManage;
import alertmanager.utils.HttpUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class Monitor {

    @Autowired
    private DingdingService dingdingService;



    @Autowired
    private ScriptsManage scriptsManage;


    public void monitor(Config config) {
        try {
            log.info("Monitoring config: {}", config.getUrl());
            CompletableFuture.runAsync(() -> {
                try {
                    replacePlaceholders(config.getHeaders());
                    MonitorResult result = processRequest(config);

                    // 检查响应
                    if (!CollectionUtils.isEmpty(config.getCheckResponse())) {
                        for (Map.Entry<String, Object> entry : config.getCheckResponse().entrySet()) {
                            if (result.getData() == null){
                                dingdingService.notification(config.getDingdingUrl(),config.getTitle(), config.getAppName(), "返回结果与值不匹配，result:"+result, config.getNotificationUser());
                            }
                            Object value = ((JSONObject)result.getData()).get(entry.getKey());
                            if (!String.valueOf(entry.getValue()).equals(String.valueOf(value))) {
                                dingdingService.notification(config.getDingdingUrl(),config.getTitle(), config.getAppName(), "返回结果与值不匹配，result:"+result, config.getNotificationUser());
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Monitor failed for config: {}", config.getUrl(), e);
                    dingdingService.notification(config.getDingdingUrl(),"服务请求失败", config.getAppName(), e.getMessage(), config.getNotificationUser());
                }
            });
        } catch (Exception e) {
            log.error("Monitor process failed", e);
        }
    }

    private void replacePlaceholders(Object data) {
        List<String> functions = scriptsManage.getFunctions();
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof String) {
                    String value = (String) entry.getValue();
                    Pattern pattern = Pattern.compile("\\{(.+?)\\}");
                    Matcher matcher = pattern.matcher(value);
                    if (matcher.matches()) {
                        String functionName = matcher.group(1);
                        //去除最后面的()
                        functionName = functionName.substring(0, functionName.length() - 2);
                        try {
                            if (functions.contains(functionName)) {
                                String result = scriptsManage.execute(value);
                                map.put(entry.getKey(), result);
                            }
                        } catch (Exception e) {
                            log.error("Function execution failed: {}", functionName, e);
                        }
                    }
                } else if (entry.getValue() instanceof Map || entry.getValue() instanceof List) {
                    replacePlaceholders(entry.getValue());
                }
            }
        } else if (data instanceof List) {
            List<Object> list = (List<Object>) data;
            for (Object item : list) {
                replacePlaceholders(item);
            }
        }
    }

    private MonitorResult processRequest(Config config) throws Exception {
        if (config.getFileConfig() != null) {
            return processFileRequest(config);
        } else {
            // 原有的处理逻辑
            if ("POST".equals(config.getMethod())) {
                return HttpUtils.post(
                        config.getUrl(),
                        config.getHeaders(),
                        JSON.toJSONString(config.getPayload()),
                        MonitorResult.class
                );
            } else {
                return HttpUtils.get(
                        config.getUrl(),
                        config.getHeaders(),
                        MonitorResult.class
                );
            }
        }
    }

    private MonitorResult processFileRequest(Config config) throws Exception {
        FileConfig fileConfig = config.getFileConfig();
        File file = new File(fileConfig.getFilePath());

        if (!file.exists() || !file.isFile()) {
            throw new IOException("File not found or is not a file: " + fileConfig.getFilePath());
        }

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();

        // 添加配置的headers
        if (config.getHeaders() != null) {
            config.getHeaders().forEach(headers::add);
        }

        if (fileConfig.isMultipart()) {
            // Multipart 请求处理
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            // 添加文件
            FileSystemResource fileResource = new FileSystemResource(file);
            body.add(fileConfig.getParamName(), fileResource);

            // 添加payload中的其他参数
            if (config.getPayload() != null) {
                config.getPayload().forEach((key, value) -> body.add(key, value));
            }

            // 添加额外的文件相关参数
            if (fileConfig.getExtraParams() != null) {
                fileConfig.getExtraParams().forEach(body::add);
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<MonitorResult> response = restTemplate.exchange(
                    config.getUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    MonitorResult.class
            );
            return response.getBody();
        } else {
            // 二进制文件直接上传处理
            headers.setContentType(MediaType.parseMediaType(fileConfig.getContentType()));
            HttpEntity<byte[]> requestEntity = new HttpEntity<>(
                    Files.readAllBytes(file.toPath()),
                    headers
            );
            ResponseEntity<MonitorResult> response = restTemplate.exchange(
                    config.getUrl(),
                    HttpMethod.POST,
                    requestEntity,
                    MonitorResult.class
            );
            return response.getBody();
        }
    }

    private List<Config> readConfigsFromFolder(String folderPath) throws IOException {
        List<Config> allConfigs = new ArrayList<>();
        File folder = new File(folderPath);

        // 检查文件夹是否存在并且是目录
        if (folder.exists() && folder.isDirectory()) {
            // 获取文件夹下的所有文件
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));

            if (files != null) {
                for (File file : files) {
                    // 读取每个JSON文件的内容
                    String content = Files.readString(file.toPath());

                    // 解析JSON内容为Config对象列表
                    List<Config> configs = JSON.parseArray(content, Config.class);

                    // 将解析到的Config对象添加到总列表中
                    allConfigs.addAll(configs);
                }
            }
        } else {
            throw new IOException("指定的路径不是一个有效的文件夹: " + folderPath);
        }

        return allConfigs;
    }

}
