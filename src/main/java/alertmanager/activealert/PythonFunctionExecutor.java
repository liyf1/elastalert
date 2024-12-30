package alertmanager.activealert;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

@Slf4j
public class PythonFunctionExecutor {
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\{([^}]+)}");
    private static String PYTHON_EXECUTABLE;  // 或指定完整路径
    private static final String REGISTRY_SCRIPT = "function_registry.py";
    private static Path tempScriptDir;
    private static String scriptsDirectoryPath;
    private static final Set<String> loadedScripts = new HashSet<>();
    private static List<String> functionNameList = new ArrayList<>();

    public static void init(String pythonPath) {
        try {
            PYTHON_EXECUTABLE = findPythonExecutable(pythonPath.trim());
            // 创建临时目录存放Python脚本
            tempScriptDir = Files.createTempDirectory("python_scripts");
            tempScriptDir.toFile().deleteOnExit();

            // 初始化函数注册表脚本
            initializeFunctionRegistry();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Python environment", e);
        }
    }

    private static String findPythonExecutable(String userPath) {
        try {
            // 首先检查用户提供的具体路径
            File pythonFile = new File(userPath);
            if (pythonFile.exists()) {
                // 验证这是否是 Python 3
                ProcessBuilder pb = new ProcessBuilder(userPath, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String versionInfo = reader.readLine();

                if (versionInfo != null && versionInfo.toLowerCase().contains("python 3")) {
                    log.info("Found Python: " + versionInfo);
                    return userPath;
                }
            }

            throw new RuntimeException("Python executable not found at: " + userPath);

        } catch (Exception e) {
            throw new RuntimeException("Error finding Python executable: " + e.getMessage(), e);
        }
    }

    private static void initializeFunctionRegistry() throws IOException {
        String registryScript = """
        import sys
        import json
        import importlib
        import inspect

        class FunctionRegistry:
            _functions = {}

            @classmethod
            def register_module_functions(cls, module):
                for name, obj in inspect.getmembers(module):
                    if inspect.isfunction(obj) and not name.startswith('_'):
                        cls._functions[name] = obj
                        print(json.dumps({"registered": name}))

            @classmethod
            def get_function(cls, name):
                if name not in cls._functions:
                    print(json.dumps({"error": f"Function '{name}' not found"}))
                    sys.exit(1)
                return cls._functions[name]

            @classmethod
            def call_function(cls, name, *args):
                try:
                    func = cls.get_function(name)
                    result = func(*args)
                    print(json.dumps({"result": str(result)}))
                except Exception as e:
                    print(json.dumps({"error": str(e)}))
                    sys.exit(1)

        # 全局注册表实例
        registry = FunctionRegistry()

        if __name__ == '__main__':
            if len(sys.argv) < 2:
                print(json.dumps({"error": "No command provided"}))
                sys.exit(1)

            command = sys.argv[1]

            if command == 'call':
                if len(sys.argv) < 3:
                    print(json.dumps({"error": "No function name provided"}))
                    sys.exit(1)
                registry.call_function(sys.argv[2], *sys.argv[3:])
        """;

        Path registryPath = tempScriptDir.resolve(REGISTRY_SCRIPT);
        Files.write(registryPath, registryScript.getBytes());
    }

    public static void loadPythonScripts(String directoryPath) {
        try {
            // 检查并安装依赖
            checkAndInstallDependencies();
            scriptsDirectoryPath = directoryPath;
            Path dir = Paths.get(directoryPath);
            if (!Files.exists(dir)) {
                throw new RuntimeException("Directory not found: " + directoryPath);
            }

            // 扫描目录下所有.py文件
            List<Path> pythonFiles = Files.walk(dir)
                    .filter(path -> path.toString().endsWith(".py"))
                    .collect(Collectors.toList());

            // 创建加载器脚本
            StringBuilder loaderScript = new StringBuilder();
            loaderScript.append("import sys\n");
            loaderScript.append("import json\n");
            loaderScript.append("import importlib.util\n");
            loaderScript.append("sys.path.append('" + dir.toAbsolutePath().toString().replace("\\", "/") + "')\n");
            loaderScript.append("from function_registry import registry\n\n");

            // 为每个Python文件添加导入代码
            for (Path pythonFile : pythonFiles) {
                if (loadedScripts.contains(pythonFile.toString())) {
                    continue;
                }

                String moduleName = pythonFile.getFileName().toString().replace(".py", "");
                String absolutePath = pythonFile.toAbsolutePath().toString().replace("\\", "/");

                loaderScript.append(String.format("""
                try:
                    spec = importlib.util.spec_from_file_location("%s", r"%s")
                    module = importlib.util.module_from_spec(spec)
                    sys.modules[spec.name] = module
                    spec.loader.exec_module(module)
                    registry.register_module_functions(module)
                    print(json.dumps({"loaded": "%s"}))
                    print(json.dumps({"registered_functions": list(registry._functions.keys())}))
                except Exception as e:
                    print(json.dumps({"error": f"Error loading {moduleName}: {str(e)}"}))
                """,
                        moduleName, absolutePath, absolutePath
                ));

                loadedScripts.add(pythonFile.toString());
            }

            // 写入并执行加载器脚本
            Path loaderPath = tempScriptDir.resolve("loader.py");
            Files.write(loaderPath, loaderScript.toString().getBytes());

            // 设置环境变量
            ProcessBuilder pb = new ProcessBuilder(PYTHON_EXECUTABLE, loaderPath.toString());
            Map<String, String> env = pb.environment();
            env.put("PYTHONPATH", dir.toAbsolutePath().toString() + File.pathSeparator + env.getOrDefault("PYTHONPATH", ""));

            // 禁用 Python 的字节码缓存
            env.put("PYTHONDONTWRITEBYTECODE", "1");

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出
            String output = readProcessOutput(process);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Failed to load Python scripts: " + output);
            }

            // 输出加载和注册信息
            log.info("Python scripts loading output: " + output);

            // 验证函数是否正确注册
            Pattern pattern = Pattern.compile("\"registered_functions\":\\s*\\[(.*?)\\]");
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                String functionsList = matcher.group(1);
                log.info("Registered functions: " + functionsList);
                //去除引号和空格
                functionsList = functionsList.replaceAll("\"", "");
                functionsList = functionsList.replaceAll(" ", "");
                functionNameList = new ArrayList<>(Arrays.asList(functionsList.split(",")));
            }

        } catch (Exception e) {
            throw new RuntimeException("Error loading Python scripts: " + e.getMessage(), e);
        }
    }

    private static void checkAndInstallDependencies() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    PYTHON_EXECUTABLE,
                    "-m",
                    "pip",
                    "install",
                    "--user",
                    "requests",
                    "urllib3"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = readProcessOutput(process);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Failed to install Python dependencies: " + output);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error installing Python dependencies: " + e.getMessage(), e);
        }
    }


    public static String processTemplate(String template) {
        Matcher matcher = FUNCTION_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String functionCall = matcher.group(1).trim();
            String replacement = executePythonFunction(functionCall);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        log.info("Processed template: " + result);
        return result.toString();
    }

    private static String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            return output.toString();
        }
    }

    private static String executePythonFunction(String functionCall) {
        try {
            // 解析函数名和参数
            String functionName;
            String[] args = new String[0];

            int paramStart = functionCall.indexOf('(');
            if (paramStart != -1) {
                functionName = functionCall.substring(0, paramStart).trim();
                String paramsStr = functionCall.substring(paramStart + 1, functionCall.length() - 1);
                if (!paramsStr.isEmpty()) {
                    args = paramsStr.split(",");
                    for (int i = 0; i < args.length; i++) {
                        args[i] = args[i].trim();
                    }
                }
            } else {
                functionName = functionCall;
            }

            // 创建执行脚本
            StringBuilder executeScript = new StringBuilder();
            executeScript.append("import sys\n");
            executeScript.append("import json\n");
            executeScript.append("import importlib\n");

            // 使用标准化的路径分隔符
            String scriptsDir = Paths.get(scriptsDirectoryPath)
                    .toAbsolutePath()
                    .toString()
                    .replace("\\", "/");

            executeScript.append("sys.path.insert(0, '" + scriptsDir + "')\n");
            executeScript.append("sys.path.insert(0, '" + tempScriptDir.toString().replace("\\", "/") + "')\n");

            executeScript.append("""
            try:
                # Import all Python files in the scripts directory
                import os
                for file in os.listdir('%s'):
                    if file.endswith('.py') and not file.startswith('__'):
                        module_name = os.path.splitext(file)[0]
                        try:
                            if module_name in sys.modules:
                                module = importlib.reload(sys.modules[module_name])
                            else:
                                module = importlib.import_module(module_name)
                            if hasattr(module, '%s'):
                                func = getattr(module, '%s')
                                result = func(%s)
                                print(json.dumps({"result": str(result)}))
                                sys.exit(0)
                        except Exception as e:
                            continue

                raise Exception("Function '%s' not found in any module")
            except Exception as e:
                print(json.dumps({"error": str(e)}))
                sys.exit(1)
            """.formatted(
                    scriptsDir.replace("\\", "/"),
                    functionName,
                    functionName,
                    args.length > 0 ? String.join(", ", args) : "",
                    functionName
            ));

            // 写入执行脚本
            Path executePath = tempScriptDir.resolve("execute_" + System.currentTimeMillis() + ".py");
            Files.write(executePath, executeScript.toString().getBytes());

            // 执行脚本
            ProcessBuilder pb = new ProcessBuilder(PYTHON_EXECUTABLE, executePath.toString());
            Map<String, String> env = pb.environment();

            // 设置跨平台的PYTHONPATH
            String pathSeparator = System.getProperty("os.name").toLowerCase().contains("windows") ? ";" : ":";
            String pythonPath = String.join(pathSeparator,
                    scriptsDir,
                    tempScriptDir.toString(),
                    env.getOrDefault("PYTHONPATH", "")
            );
            env.put("PYTHONPATH", pythonPath);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = readProcessOutput(process);
            int exitCode = process.waitFor();

            // 清理临时文件
            Files.deleteIfExists(executePath);

            if (exitCode != 0) {
                throw new RuntimeException("Python function execution failed: " + output);
            }

            return parseResult(output);

        } catch (Exception e) {
            throw new RuntimeException("Error executing Python function: " + e.getMessage(), e);
        }
    }

    private static String parseResult(String output) {
        try {
            // 寻找最后一个JSON对象
            int lastJsonStart = output.lastIndexOf("{");
            int lastJsonEnd = output.lastIndexOf("}");

            if (lastJsonStart >= 0 && lastJsonEnd >= 0) {
                String jsonStr = output.substring(lastJsonStart, lastJsonEnd + 1);
                if (jsonStr.contains("\"result\":")) {
                    int start = jsonStr.indexOf("\"result\":") + 9;
                    int end = jsonStr.lastIndexOf("}");
                    String result = jsonStr.substring(start, end);
                    if (result.startsWith("\"") && result.endsWith("\"")) {
                        result = result.substring(1, result.length() - 1);
                    }
                    return result;
                }
            }
            return output;
        } catch (Exception e) {
            return output;
        }
    }

    // 获取已注册的函数列表
    public static List<String> listRegisteredFunctions() {
        return functionNameList;
    }
}