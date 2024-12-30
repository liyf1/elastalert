package alertmanager.activealert;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ScriptsManage {

    @Value("${activealert.python.path}")
    private String pythonPath;

    @Value("${activealert.python.script.directory}")
    private String scriptDirectory;

    @PostConstruct
    public void init(){
        PythonFunctionExecutor.init(pythonPath);
        PythonFunctionExecutor.loadPythonScripts(scriptDirectory);
    }

    public List<String> getFunctions(){
        return PythonFunctionExecutor.listRegisteredFunctions();
    }

    public String execute(String functionName){
        return PythonFunctionExecutor.processTemplate(functionName);
    }
}
