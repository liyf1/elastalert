package alertmanager.elastalert;

import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.response.OapiRobotSendResponse;
import com.taobao.api.ApiException;
import org.apache.commons.collections4.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class DingdingAlert extends AlertConfig {

    private static final long serialVersionUID = 1L;
    private static String WEBHOOK_URL = "https://oapi.dingtalk.com/robot/send?access_token=28f85ad3df385aa73882338e2f84a751656e67a8bf93d200696f98ddf33e91da";

    DingdingAlert(Map<String, Object> config) {
        super(AlertType.DINGDING, (List)config.get("dingding_users"));
        WEBHOOK_URL = (String) config.get("webhook_url");
    }

    @Override
    public void sendAlert(AlertContext context) {
        DingTalkClient client = new DefaultDingTalkClient(WEBHOOK_URL);
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("markdown");
        OapiRobotSendRequest.Markdown markdown = new OapiRobotSendRequest.Markdown();
        markdown.setTitle("【告警】监控告警");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); // 指定时区为东八区
        String time = sdf.format(new Date());
        StringBuilder recipientsStr = new StringBuilder();
        if (CollectionUtils.isNotEmpty(recipients)){
            for (String recipient : recipients){
                recipientsStr.append("@"+recipient);
                recipientsStr.append(" ");
            }
        }
        markdown.setText("# \uD83D\uDEA8 组件服务告警\n" +
                "\n\n" +
                "**告警名称**: "+ context.getRuleName() +"  \n" +
                "\n" +
                "**时间**: "+ time +"  \n" +
                "\n" +
                "**告警组件**: " + context.getRuleName() +" \n" +
                "\n" +
                "**描述**: "+context.getAlertText()+"\n"+
                "\n" +
                "**负责人**: " +recipientsStr+
                "\n\n" +
                "---\n");
        request.setMarkdown(markdown);
        try {
            OapiRobotSendResponse response = client.execute(request);
        } catch (ApiException e) {
            throw new RuntimeException(e);
        }
    }
}
