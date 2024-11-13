package alertmanager.activealert;

import lombok.Data;

@Data
public class MonitorResult {
    private String code;
    private String message;
    private Object data;
}