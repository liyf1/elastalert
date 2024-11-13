package alertmanager.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfigFilePathManage {

    @Value("${activealert.config.path}")
    public String activeAlertConfigPath;

    @Value("${elastalert.config.path}")
    public String elastAlertConfigPath;
}
