//package alertmanager.activealert;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//@Component
//public class NotificationJob {
//
//    @Autowired
//    private Monitor monitor;
//
//    @Scheduled(cron = "0 0 9-18 * * *", zone = "Asia/Shanghai")
//    public void sendNotification() {
//        monitor.monitor();
//    }
//
//}
