package alertmanager;

import alertmanager.config.EnableElastalert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableScheduling
@EnableElastalert
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration.class)
public class AlertManagerServApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertManagerServApplication.class, args);
    }

}
