package org.utbot.python.framework.external;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.utbot.python.utils.Cleaner;
import org.utbot.python.utils.TemporaryFileManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class PythonUtBotJavaApiTest {

    @BeforeEach
    public void setUp() {
        Cleaner.INSTANCE.restart();
    }

    @AfterEach
    public void tearDown() {
        Cleaner.INSTANCE.doCleaning();
    }

    @Test
    public void testSimpleFunction() {
        String code = "def f(x: int, y: int):\n    return x + y";
        File fileWithCode = TemporaryFileManager.INSTANCE.createTemporaryFile(code, "simple_function.py", null, false);
        String pythonRunRoot = fileWithCode.getParentFile().getAbsolutePath();
        String moduleFilename = fileWithCode.getAbsolutePath();
        String moduleName = "simple_function";
        String methodName = "f";
        PythonObjectName testMethodName = new PythonObjectName(moduleName, methodName);
        PythonTestMethodInfo methodInfo = new PythonTestMethodInfo(testMethodName, moduleFilename, null);
        ArrayList<PythonTestMethodInfo> testMethods = new ArrayList<>(1);
        testMethods.add(methodInfo);
        Set<String> directoriesForSysPath = new HashSet<>();
        directoriesForSysPath.add(pythonRunRoot);
        String pythonPath = "C:\\Users\\tWX1238545\\IdeaProjects\\UTBotJava\\utbot-python\\samples\\venv\\Scripts\\python.exe";
        String testCode = PythonUtBotJavaApi.generateUnitTestsCode(
                testMethods,
                pythonPath,
                pythonRunRoot,
                directoriesForSysPath,
                10_000,
                1_000
        );
        Assertions.assertTrue(testCode.length() > 0);
    }
}
