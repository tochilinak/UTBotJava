package org.utbot.python.framework.external

import org.utbot.common.PathUtil.toPath
import org.utbot.python.PythonMethodHeader
import org.utbot.python.PythonTestGenerationProcessor
import org.utbot.python.PythonTestSet
import org.utbot.python.framework.api.python.PythonClassId
import org.utbot.python.framework.codegen.model.Unittest
import java.io.File

object PythonUtBotJavaApi {
    @JvmStatic
    @JvmOverloads
    fun generateUnitTests (
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Set<String>,
        timeout: Long,
        timeoutForRun: Long,
    ): List<PythonTestSet> {
        val processor = initPythonTestGeneratorProcessor(
            testMethods,
            pythonPath,
            pythonRunRoot,
            directoriesForSysPath,
            timeout,
            timeoutForRun,
        ) {}
        return processor.collectTestCases()
    }

    @JvmStatic
    @JvmOverloads
    fun generateUnitTestsCode (
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Set<String>,
        timeout: Long,
        timeoutForRun: Long,
    ): String {
        var code = ""
        val processor = initPythonTestGeneratorProcessor(
            testMethods,
            pythonPath,
            pythonRunRoot,
            directoriesForSysPath,
            timeout,
            timeoutForRun
        ) {
            code = it
        }
        val tests = processor.collectTestCases()
        processor.renderTests(tests)
        return code
    }

    private fun initPythonTestGeneratorProcessor (
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Set<String>,
        timeout: Long,
        timeoutForRun: Long,
        writeTestTextToFile: (String) -> Unit,
    ): PythonTestGenerationProcessor {
        val pythonFilePath = testMethods.map { it.moduleFilename }.let {
            if (it.size != 1) {
                throw IllegalArgumentException("All test methods should be from one module")
            }
            it.first()
        }
        val contentFile = File(pythonFilePath)
        val pythonFileContent = contentFile.readText()

        val pythonModule = testMethods.map { it.methodName.moduleName }.let {
            if (it.size != 1) {
                throw IllegalArgumentException("All test methods should be from one module")
            }
            it.first()
        }

        val pythonMethods = testMethods.map {
            PythonMethodHeader(
                it.methodName.name,
                it.moduleFilename,
                it.containingClassName?.let { objName ->
                    PythonClassId(objName.moduleName, objName.name)
                })
        }

        val containingClass = testMethods.map { it.containingClassName }.let {
            if (it.size != 1) {
                throw IllegalArgumentException("All test methods should be from one class or all from top level")
            }
            it.first()
        }

        return PythonTestGenerationProcessor(
            pythonPath,
            pythonFilePath,
            pythonFileContent,
            directoriesForSysPath,
            pythonModule,
            pythonMethods,
            containingClass?.fullName,
            timeout,
            Unittest,
            timeoutForRun,
            writeTestTextToFile,
            pythonRunRoot.toPath(),
        )
    }
}