package alertmanager.elastalert;

/**
 * 告警异常
 */
public class AlertException extends RuntimeException {
    public AlertException(String message) {
        super(message);
    }

    public AlertException(String message, Throwable cause) {
        super(message, cause);
    }
}
