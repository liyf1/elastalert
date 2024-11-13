package alertmanager.activealert;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.Map;

@Data
public class Config {
    private String url;
    private String method;
    private String appName;
    @JSONField(name = "need_verify")
    private boolean needVerify;
    private int timeout = 10;
    private Map<String, String> headers;
    private Map<String, Object> payload;
    @JSONField(name = "check_response")
    private Map<String, Object> checkResponse;
    private String title;
    private String email;
    @JSONField(name = "notification_user")
    private String notificationUser;
    @JSONField(name = "file_config")
    private FileConfig fileConfig;
}