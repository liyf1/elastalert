package alertmanager.activealert;

import alertmanager.config.ConfigFilePathManage;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

@RestController
@Configuration
@Slf4j
public class DynamicScheduleConfig implements SchedulingConfigurer {

    @Autowired
    private ConfigFilePathManage configFilePathManage;

    @Autowired
    private Monitor monitor;

    private ScheduledTaskRegistrar taskRegistrar;
    private ScheduledTask scheduledTask;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        this.taskRegistrar = taskRegistrar;
        scheduleTask();
    }

    private void scheduleTask() {
        // 如果已有任务在运行，先取消它
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
        List<Config> configs = null;
        try {
            configs = readConfigsFromFolder(configFilePathManage.activeAlertConfigPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (Config config : configs) {
            scheduledTask = taskRegistrar.scheduleCronTask(
                    new CronTask(
                            () -> monitor.monitor(config),
                            new CronTrigger(
                                    config.getCronExpression(),
                                    TimeZone.getTimeZone("Asia/Shanghai")
                            )
                    )
            );
        }
    }

    // 提供重新加载配置的方法
    @PostMapping("/reload-schedule")
    public void reloadSchedule() {
        scheduleTask();
        log.info("Schedule configuration reloaded");
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
