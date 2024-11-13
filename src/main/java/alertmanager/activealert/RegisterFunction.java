package alertmanager.activealert;

import alertmanager.utils.HttpUtils;
import com.alibaba.fastjson.JSON;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegisterFunction {

    public static String getTokensFromAibase() throws IOException {
        String url = "https://39.101.74.2:5001/service/api/v1/oauth/172e35c318a04612b7d65169bbf68366/token";

        HashMap<String, Object> params = new HashMap<>();
        params.put("grant_type", "client_credentials");
        params.put("client_id", "c618cc0a346d4411837e47ac9a828290");
        params.put("client_secret", "ee54a00fd04b4c20b18af9c5a9e0564b");

        MonitorResult result = HttpUtils.post(url, null, JSON.toJSONString(params), MonitorResult.class);
        return "Bearer " + ((Map)result.getData()).get("access_token");
    }

    public static String generateMsgId() {
        return "monitor_" + UUID.randomUUID().toString();
    }
}
