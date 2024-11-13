package alertmanager.elastalert;

/**
 * 规则验证异常
 */
public class ValidationException extends Exception {
    public ValidationException(String message) {
        super(message);
    }
}