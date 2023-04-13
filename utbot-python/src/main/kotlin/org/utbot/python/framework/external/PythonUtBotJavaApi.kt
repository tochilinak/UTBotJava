package org.utbot.python.framework.external

import mu.KLogger
import mu.KotlinLogging
import org.utbot.common.PathUtil.toPath
import org.utbot.framework.UtSettings
import org.utbot.python.PythonMethodHeader
import org.utbot.python.PythonTestGenerationProcessor
import org.utbot.python.PythonTestSet
import org.utbot.python.framework.api.python.PythonClassId
import org.utbot.python.framework.codegen.model.Unittest
import org.utbot.python.utils.RequirementsUtils
import java.io.File

object PythonUtBotJavaApi {
    private val logger: KLogger = KotlinLogging.logger {}

    /**
     * Generate test sets
     *
     * @param testMethods methods for test generation
     * @param pythonPath  a path to the Python executable file
     * @param pythonRunRoot a path to the directory where test sets will be executed
     * @param directoriesForSysPath a collection of strings that specifies the additional search path for modules, usually it is only project root
     * @param timeout a timeout to the test generation process (in milliseconds)
     * @param executionTimeout a timeout to one concrete execution
     */
    @JvmStatic
    fun generateTestSets (
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Collection<String>,
        timeout: Long,
        executionTimeout: Long = UtSettings.concreteExecutionDefaultTimeoutInInstrumentedProcessMillis,
    ): List<PythonTestSet> {
        val processor = initPythonTestGeneratorProcessor(
            testMethods,
            pythonPath,
            pythonRunRoot,
            directoriesForSysPath.toSet(),
            timeout,
            executionTimeout,
        ) {}
        return processor.collectTestCases()
    }

    /**
     * Generate test sets code
     *
     * @param testMethods methods for test generation
     * @param pythonPath  a path to the Python executable file
     * @param pythonRunRoot a path to the directory where test sets will be executed
     * @param directoriesForSysPath a collection of strings that specifies the additional search path for modules, usually it is only project root
     * @param timeout a timeout to the test generation process (in milliseconds)
     * @param executionTimeout a timeout to one concrete execution
     */
    @JvmStatic
    fun generate (
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Collection<String>,
        timeout: Long,
        executionTimeout: Long,
    ): String {
        var code = ""
        val processor = initPythonTestGeneratorProcessor(
            testMethods,
            pythonPath,
            pythonRunRoot,
            directoriesForSysPath.toSet(),
            timeout,
            executionTimeout
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
                throw IllegalArgumentException("All test methods should be from one file")
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
                throw IllegalArgumentException("All test methods should be from one class or only top level")
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
            requirementsAreNotInstalledAction = {
                val requirements = RequirementsUtils.requirements.joinToString(", ")
                logger.error { "Please install requirements in Python environment: $requirements" }
                PythonTestGenerationProcessor.MissingRequirementsActionResult.NOT_INSTALLED
            },
            notGeneratedTestsAction = {
                val functionNames = it.joinToString(", ")
                logger.warn { "Cannot create tests for the following functions: $functionNames" }
            }
        )
    }
}