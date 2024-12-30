package alertmanager.elastalert;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 告警缓存记录
 */
@Data
public class AlertEntry {
    private String id;
    private String ruleName;
    private Instant timestamp;
    private Map<String, Object> context;
    private ESAlertManager.AlertStatus status;
    private int retryCount;

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public boolean shouldRetry() {
        return this.retryCount < 3 && this.status == ESAlertManager.AlertStatus.FAILURE;
    }
}
