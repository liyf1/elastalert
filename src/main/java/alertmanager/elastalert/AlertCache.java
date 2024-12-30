package alertmanager.elastalert;

import java.time.Duration;
import java.util.Optional;

/**
 * 告警缓存接口 - 用于告警去重
 */
public interface AlertCache {
    /**
     * 获取告警记录
     */
    Optional<AlertEntry> get(String alertId);

    /**
     * 保存告警记录
     */
    void put(String alertId, AlertEntry entry, Duration ttl);

    /**
     * 检查告警是否存在
     */
    boolean exists(String alertId);

    /**
     * 删除告警记录
     */
    void delete(String alertId);

    /**
     * 清理过期记录
     */
    void cleanup();

    /**
     * 关闭缓存
     */
    void shutdown();
}
