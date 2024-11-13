package alertmanager.elastalert;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 健康检查状态类
 */
@Data
public class HealthStatus {
    private boolean esHealthy;
    private Map<String, RuleHealthStatus> ruleHealth;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant lastCheckTime;
    private String status;  // HEALTHY, DEGRADED, UNHEALTHY
    private List<String> issues;

    public HealthStatus() {
        this.lastCheckTime = Instant.now();
        this.issues = new ArrayList<>();
    }
}
