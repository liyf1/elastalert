package alertmanager.elastalert;

/**
 * 规则变更监听器接口
 */
public interface RuleChangeListener {
    void onRuleAdded(Rule rule);
    void onRuleUpdated(Rule rule);
    void onRuleDeleted(Rule rule);
    void onRuleLoadError(String rulePath, Exception error);
}
