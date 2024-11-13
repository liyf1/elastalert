package alertmanager.utils;

import com.alibaba.fastjson2.JSON;
import okhttp3.*;
import org.springframework.util.CollectionUtils;

import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HttpUtils {

    private static final OkHttpClient client;

    static {
        X509TrustManager manager = SSLSocketClientUtil.getX509TrustManager();
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(SSLSocketClientUtil.getSocketFactory(manager), manager)// 忽略校验
                .hostnameVerifier(SSLSocketClientUtil.getHostnameVerifier())//忽略校验
                .build();
    }

    public static <T> T get(String url, Map<String, String> headers, Class<T> clazz) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        if (headers != null) {
            request = request.newBuilder().headers(Headers.of(headers)).build();
        }
        return getRsult(clazz, request);
    }

    private static <T> T getRsult(Class<T> clazz, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            assert response.body() != null;
            String result = response.body().string();
            return JSON.parseObject(result, clazz);
        }
    }

    public static <T> T post(String url, Map<String, String> headers, String jsonBody, Class<T> clazz) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        if (!CollectionUtils.isEmpty(headers)) {
            request = request.newBuilder().headers(Headers.of(headers)).build();
        }
        return getRsult(clazz, request);
    }


}
