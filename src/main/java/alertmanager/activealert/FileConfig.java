package alertmanager.activealert;

import lombok.Data;

import java.util.Map;

@Data
public class FileConfig {
    // 本地文件路径
    private String filePath;
    // 文件在请求中的参数名
    private String paramName;
    // 文件类型/MimeType
    private String contentType;
    // 是否作为multipart形式上传
    private boolean isMultipart = true;
    // 额外的文件相关参数
    private Map<String, String> extraParams;
}

