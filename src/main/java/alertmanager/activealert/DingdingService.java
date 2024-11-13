package alertmanager.activealert;

import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.response.OapiRobotSendResponse;
import com.taobao.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

@Component
public class DingdingService {

    private static final Logger log = LoggerFactory.getLogger(DingdingService.class);

    public void notification(String title, String appName, String content, String notificationUser) {

        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/robot/send?access_token=28f85ad3df385aa73882338e2f84a751656e67a8bf93d200696f98ddf33e91da");
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("markdown");
        OapiRobotSendRequest.Markdown markdown = new OapiRobotSendRequest.Markdown();
        markdown.setTitle("【告警】监控告警");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); // 指定时区为东八区
        String time = sdf.format(new Date());
        markdown.setText("# \uD83D\uDEA8 组件服务告警\n" +
                "\n\n" +
                "**告警名称**: "+ title +"  \n" +
                "\n" +
                "**时间**: "+ time +"  \n" +
                "\n" +
                "**告警组件**: " + appName +" \n" +
                "\n" +
                "**描述**: "+content+"\n"+
                "\n" +
                "**负责人**: @" +notificationUser+
                "\n\n" +
                "---\n");
        request.setMarkdown(markdown);
        try {
            OapiRobotSendResponse response = client.execute(request);
        } catch (ApiException e) {
            log.error("钉钉机器人发送失败");
            throw new RuntimeException(e);
        }
    }
}
