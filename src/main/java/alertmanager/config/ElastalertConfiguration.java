package alertmanager.config;

import alertmanager.elastalert.ElastAlertApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ElastalertConfiguration {

    @Autowired
    private ConfigFilePathManage configFilePathManage;

    @Bean
    public ElastAlertApplication elastAlertApplication() {
        ElastAlertApplication app = null;

        try {
            app = new ElastAlertApplication(configFilePathManage.elastAlertConfigPath);
            // 启动一个线程来保持应用运行
            ElastAlertApplication finalApp = app;
            new Thread(() -> {
                try {
                    finalApp.start();
                    while (finalApp.isRunning()) {
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    log.error("应用运行中断", e);
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            log.error("应用运行错误", e);
            System.exit(1); // 退出程序，返回非零状态码表示异常
        }
        return app;
    }
}
