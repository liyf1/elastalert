package alertmanager.elastalert;

import lombok.Data;

import java.time.Instant;

/**
 * 规则健康状态类
 */
@Data
public class RuleHealthStatus {
    private String ruleName;
    private String status;  // RUNNING, FAILED, STOPPED
    private Instant lastExecutionTime;
    private int consecutiveFailures;
    private String lastError;
    private long averageExecutionTime;
    private int matchCount;
    private int alertCount;
}