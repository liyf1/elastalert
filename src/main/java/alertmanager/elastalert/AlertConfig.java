package alertmanager.elastalert;

import lombok.Getter;

import java.util.List;

/**
 * 告警配置基类
 */
@Getter
public abstract class AlertConfig {

    enum AlertType{
        EMAIL,
        SLACK,
        WEBHOOK,
        KIBANA,
        ALERTMANAGER,
        PAGERDUTY,
        OPSGENIE,
        JIRA,
        GRAFANA,
        PAGERTREE,
        CUSTOM,
        DEBUG,
        DINGDING
    }

    List<String> recipients;

    AlertConfig(AlertType type,List<String> recipients){
        this.type = type.name();
        this.recipients = recipients;
    }

    protected String type;

    public abstract void sendAlert(Rule rule,AlertContext context);
}
