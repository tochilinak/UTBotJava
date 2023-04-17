package org.utbot.python.framework.external

import mu.KLogger
import mu.KotlinLogging
import org.utbot.common.PathUtil.toPath
import org.utbot.framework.UtSettings
import org.utbot.framework.codegen.domain.TestFramework
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.python.PythonMethodHeader
import org.utbot.python.PythonTestGenerationProcessor
import org.utbot.python.PythonTestSet
import org.utbot.python.framework.api.python.PythonClassId
import org.utbot.python.framework.codegen.model.Pytest
import org.utbot.python.framework.codegen.model.Unittest
import org.utbot.python.utils.RequirementsUtils
import org.utbot.python.utils.Success
import org.utbot.python.utils.findCurrentPythonModule
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
        )
        return processor.collectTestCases()
    }

    /**
     * Generate test sets code
     *
     * @param testSets a list of test sets
     * @param pythonRunRoot a path to the directory where test sets will be executed
     * @param directoriesForSysPath a collection of strings that specifies the additional search path for modules, usually it is only project root
     * @param testFramework a test framework (Unittest or Pytest)
     */
    @JvmStatic
    fun renderTestSets (
        testSets: List<PythonTestSet>,
        pythonRunRoot: String,
        directoriesForSysPath: Collection<String>,
        testFramework: TestFramework = Unittest,
    ): String {
        if (testSets.isEmpty()) return ""

        require(testFramework is Unittest || testFramework is Pytest) { "TestFramework should be Unittest or Pytest" }

        val containingClass = testSets.map { it.method.containingPythonClassId } .toSet().let {
            require(it.size == 1) {"All test methods should be from one class or only top level"}
            it.first()
        }

        val containingFile = testSets.map { it.method.moduleFilename } .toSet().let {
            require(it.size == 1) {"All test methods should be from one module"}
            it.first()
        }
        val moduleUnderTest = findCurrentPythonModule(directoriesForSysPath, containingFile)
        require(moduleUnderTest is Success)

        val classId = PythonTestGenerationProcessor.constructTestClassId(
            containingClass?.name,
            moduleUnderTest.value
        )
        val methodIds = PythonTestGenerationProcessor.constructMethodIds(classId, testSets)
        val allImports =
            PythonTestGenerationProcessor.collectImports(testSets, methodIds, testFramework) +
            PythonTestGenerationProcessor.collectSysPaths(pythonRunRoot.toPath(), directoriesForSysPath.toSet())

        val context = UtContext(this::class.java.classLoader)
        return PythonTestGenerationProcessor.generateTestCode(
            context,
            classId,
            testSets,
            methodIds,
            allImports,
            testFramework,
        )
    }

    /**
     * Generate test sets and render code
     *
     * @param testMethods methods for test generation
     * @param pythonPath  a path to the Python executable file
     * @param pythonRunRoot a path to the directory where test sets will be executed
     * @param directoriesForSysPath a collection of strings that specifies the additional search path for modules, usually it is only project root
     * @param timeout a timeout to the test generation process (in milliseconds)
     * @param executionTimeout a timeout to one concrete execution
     * @param testFramework a test framework (Unittest or Pytest)
     */
    @JvmStatic
    fun generate(
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Collection<String>,
        timeout: Long,
        executionTimeout: Long = UtSettings.concreteExecutionDefaultTimeoutInInstrumentedProcessMillis,
        testFramework: TestFramework = Unittest,
    ): String {
        val testSets =
            generateTestSets(testMethods, pythonPath, pythonRunRoot, directoriesForSysPath, timeout, executionTimeout)
        return renderTestSets(testSets, pythonRunRoot, directoriesForSysPath, testFramework)
    }

    private fun initPythonTestGeneratorProcessor (
        testMethods: List<PythonTestMethodInfo>,
        pythonPath: String,
        pythonRunRoot: String,
        directoriesForSysPath: Set<String>,
        timeout: Long,
        timeoutForRun: Long,
    ): PythonTestGenerationProcessor {
        val pythonFilePath = testMethods.map { it.moduleFilename }.let {
            require(it.size == 1) {"All test methods should be from one file"}
            it.first()
        }
        val contentFile = File(pythonFilePath)
        val pythonFileContent = contentFile.readText()

        val pythonModule = testMethods.map { it.methodName.moduleName }.let {
            require(it.size == 1) {"All test methods should be from one module"}
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

        val containingClass = testMethods.map { it.containingClassName } .toSet().let {
            require(it.size == 1) {"All test methods should be from one class or only top level"}
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
            {},
            pythonRunRoot.toPath(),
            requirementsAreNotInstalledAction = {
                val requirements = RequirementsUtils.requirements.joinToString(", ")
                logger.error { "Please install requirements in Python environment: $requirements" }
                PythonTestGenerationProcessor.MissingRequirementsActionResult.NOT_INSTALLED
            },
        )
    }
}