package org.utbot.python

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import mu.KLogger
import mu.KotlinLogging
import org.parsers.python.PythonParser
import org.utbot.framework.codegen.domain.RuntimeExceptionTestsBehaviour
import org.utbot.python.framework.codegen.model.PythonSysPathImport
import org.utbot.python.framework.codegen.model.PythonSystemImport
import org.utbot.python.framework.codegen.model.PythonUserImport
import org.utbot.framework.codegen.domain.TestFramework
import org.utbot.framework.codegen.domain.models.CgMethodTestSet
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.UtClusterInfo
import org.utbot.framework.plugin.api.UtExecutionSuccess
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.framework.plugin.api.util.withUtContext
import org.utbot.python.code.PythonCode
import org.utbot.python.framework.api.python.PythonClassId
import org.utbot.python.framework.api.python.PythonMethodId
import org.utbot.python.framework.api.python.PythonModel
import org.utbot.python.framework.api.python.RawPythonAnnotation
import org.utbot.python.framework.api.python.util.pythonAnyClassId
import org.utbot.python.framework.codegen.model.PythonCodeGenerator
import org.utbot.python.framework.codegen.model.PythonImport
import org.utbot.python.newtyping.PythonFunctionDefinition
import org.utbot.python.newtyping.general.CompositeType
import org.utbot.python.newtyping.getPythonAttributes
import org.utbot.python.newtyping.mypy.MypyAnnotationStorage
import org.utbot.python.newtyping.mypy.MypyException
import org.utbot.python.newtyping.mypy.readMypyAnnotationStorageAndInitialErrors
import org.utbot.python.newtyping.mypy.setConfigFile
import org.utbot.python.utils.mypy.MypyAnnotations
import org.utbot.python.utils.Cleaner
import org.utbot.python.utils.RequirementsUtils.requirementsAreInstalled
import org.utbot.python.utils.getLineOfFunction
import org.utbot.python.utils.mypy.MypyConfig
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.pathString

class PythonTestGenerationProcessor(
    val pythonPath: String,
    val pythonFilePath: String,
    val pythonFileContent: String,
    val directoriesForSysPath: Set<String>,
    val currentPythonModule: String,
    val pythonMethods: List<PythonMethodHeader>,
    val containingClassName: String?,
    val timeout: Long,
    val testFramework: TestFramework,
    val timeoutForRun: Long,
    val writeTestTextToFile: (String) -> Unit,
    val pythonRunRoot: Path,
    val doNotCheckRequirements: Boolean = false,
    val withMinimization: Boolean = true,
    val runtimeExceptionTestsBehaviour: RuntimeExceptionTestsBehaviour = RuntimeExceptionTestsBehaviour.FAIL,
    val isCanceled: () -> Boolean = { false },
    val checkingRequirementsAction: () -> Unit = {},
    val installingRequirementsAction: () -> Unit = {},
    val requirementsAreNotInstalledAction: () -> MissingRequirementsActionResult = {
        MissingRequirementsActionResult.NOT_INSTALLED
    },
    val startedLoadingPythonTypesAction: () -> Unit = {},
    val startedTestGenerationAction: () -> Unit = {},
    val notGeneratedTestsAction: (List<String>) -> Unit = {}, // take names of functions without tests
    val processMypyWarnings: (List<String>) -> Unit = {},
    val processCoverageInfo: (String) -> Unit = {},
    val startedCleaningAction: () -> Unit = {},
    val finishedAction: (List<String>) -> Unit = {},  // take names of functions with generated tests
) {
    private val logger: KLogger = KotlinLogging.logger {}

    /*
    Returns `true` if requirements are installed
     */
    private fun checkRequirements(): Boolean {
        if (!doNotCheckRequirements) {
            checkingRequirementsAction()
            if (!requirementsAreInstalled(pythonPath)) {
                installingRequirementsAction()
                val result = requirementsAreNotInstalledAction()
                if (result == MissingRequirementsActionResult.NOT_INSTALLED)
                    return false
            }
        }
        return true
    }

    private fun configurateMypy(): MypyConfig {
        val mypyConfigFile = setConfigFile(directoriesForSysPath)
        val (mypyStorage, report) = readMypyAnnotationStorageAndInitialErrors(
            pythonPath,
            pythonFilePath,
            currentPythonModule,
            mypyConfigFile
        )
        return MypyConfig(mypyStorage, report, mypyConfigFile)
    }

    private fun constructTestClassId(): PythonClassId {
        return if (containingClassName == null) {
            PythonClassId(currentPythonModule, "TopLevelFunctions")
        } else {
            PythonClassId(currentPythonModule, containingClassName)
        }
    }

    private fun constructMethodIds(classId: PythonClassId, notEmptyTests: List<PythonTestSet>): Map<PythonMethod, PythonMethodId> {
        return notEmptyTests.associate {
            it.method to PythonMethodId(
                classId,
                it.method.name,
                RawPythonAnnotation(pythonAnyClassId.name),
                it.method.arguments.map { argument ->
                    argument.annotation?.let { annotation ->
                        RawPythonAnnotation(annotation)
                    } ?: pythonAnyClassId
                }
            )
        }
    }

    fun processTestGeneration() {
        val tests = collectTestCases()
        renderTests(tests)
    }

    fun renderTests(tests: List<PythonTestSet>) {
        val (notEmptyTests, emptyTestSets) = tests.partition { it.executions.isNotEmpty() }
        if (emptyTestSets.isNotEmpty()) {
            notGeneratedTestsAction(emptyTestSets.map { it.method.name })
        }

        if (notEmptyTests.isEmpty())
            return

        val classId = constructTestClassId()
        val methodIds = constructMethodIds(classId, notEmptyTests)

        val allImports = collectImports(
            notEmptyTests, methodIds
        )

        val testCode = generateTestCode(classId, notEmptyTests, methodIds, allImports)
        writeTestTextToFile(testCode)

        val coverageInfo = getCoverageInfo(notEmptyTests)
        processCoverageInfo(coverageInfo)

        val mypyReport = getMypyReport(notEmptyTests, pythonFileContent)
        if (mypyReport.isNotEmpty())
            processMypyWarnings(mypyReport)

        finishedAction(notEmptyTests.map { it.method.name })
    }

    fun collectTestCases(): List<PythonTestSet> {
        Cleaner.restart()

        val tests = mutableListOf<PythonTestSet>()
        try {
            if (checkRequirements()) {
                startedLoadingPythonTypesAction()
                val mypyConfig = configurateMypy()

                startedTestGenerationAction()
                tests.addAll(generateTestCase(mypyConfig))
            }
        } catch (ex: MypyException) {
            logger.error { ex.message }
        } catch (ex: Exception) {
            logger.error { ex }
        } finally {
            startedCleaningAction()
            Cleaner.doCleaning()
        }
        return tests
    }

    private fun generateTestCase(mypyConfig: MypyConfig): List<PythonTestSet> {
        val startTime = System.currentTimeMillis()

        val testCaseGenerator = PythonTestCaseGenerator(
            withMinimization = withMinimization,
            directoriesForSysPath = directoriesForSysPath,
            curModule = currentPythonModule,
            pythonPath = pythonPath,
            fileOfMethod = pythonFilePath,
            isCancelled = isCanceled,
            timeoutForRun = timeoutForRun,
            sourceFileContent = pythonFileContent,
            mypyStorage = mypyConfig.mypyStorage,
            mypyReportLine = mypyConfig.mypyReportLine,
            mypyConfigFile = mypyConfig.mypyConfigFile,
        )

        val until = startTime + timeout
        return pythonMethods.mapIndexed { index, methodHeader ->
            val methodsLeft = pythonMethods.size - index
            val now = System.currentTimeMillis()
            val localUntil = now + (until - now) / methodsLeft
            val method = findMethodByHeader(mypyConfig.mypyStorage, methodHeader, currentPythonModule, pythonFileContent)
            testCaseGenerator.generate(method, localUntil)
        }
    }

    private fun collectImports(
        notEmptyTests: List<PythonTestSet>,
        methodIds: Map<PythonMethod, PythonMethodId>,
    ): Set<PythonImport> {

        val importParamModules = notEmptyTests.flatMap { testSet ->
            testSet.executions.flatMap { execution ->
                (execution.stateBefore.parameters + execution.stateAfter.parameters +
                        listOf(execution.stateBefore.thisInstance, execution.stateAfter.thisInstance))
                    .filterNotNull()
                    .flatMap { utModel ->
                        (utModel as PythonModel).let {
                            it.allContainingClassIds.map { classId ->
                                PythonUserImport(importName_ = classId.moduleName)
                            }
                        }
                    }
            }
        }
        val importResultModules = notEmptyTests.flatMap { testSet ->
            testSet.executions.mapNotNull { execution ->
                if (execution.result is UtExecutionSuccess) {
                    (execution.result as UtExecutionSuccess).let { result ->
                        (result.model as PythonModel).let {
                            it.allContainingClassIds.map { classId ->
                                PythonUserImport(importName_ = classId.moduleName)
                            }
                        }
                    }
                } else null
            }.flatten()
        }
        val testRootModules = notEmptyTests.mapNotNull { testSet ->
            methodIds[testSet.method]?.rootModuleName?.let { PythonUserImport(importName_ = it) }
        }
        val sysImport = PythonSystemImport("sys")
        val sysPathImports = relativizePaths(pythonRunRoot, directoriesForSysPath)
            .map { PythonSysPathImport(it) }
            .filterNot { it.sysPath.isEmpty() }

        val testFrameworkModule =
            testFramework.testSuperClass?.let { PythonUserImport(importName_ = (it as PythonClassId).rootModuleName) }

        return (
                importParamModules + importResultModules + testRootModules + sysPathImports + listOf(
                    testFrameworkModule,
                    sysImport
                )
                )
            .filterNotNull()
//                .filterNot { it.importName == pythonBuiltinsModuleName }
            .toSet()
    }

    fun generateTestCode(
        classId: PythonClassId,
        notEmptyTests: List<PythonTestSet>,
        methodIds: Map<PythonMethod, PythonMethodId>,
        allImports: Set<PythonImport>,
    ): String {
        val paramNames = notEmptyTests.associate { testSet ->
            var params = testSet.method.arguments.map { it.name }
            if (testSet.method.hasThisArgument) {
                params = params.drop(1)
            }
            methodIds[testSet.method] as ExecutableId to params
        }.toMutableMap()

        val context = UtContext(this::class.java.classLoader)
        withUtContext(context) {
            val codegen = PythonCodeGenerator(
                classId,
                paramNames = paramNames,
                testFramework = testFramework,
                testClassPackageName = "",
                runtimeExceptionTestsBehaviour = runtimeExceptionTestsBehaviour,
            )
            val testCode = codegen.pythonGenerateAsStringWithTestReport(
                notEmptyTests.map { testSet ->
                    val intRange = testSet.executions.indices
                    val clusterInfo = listOf(Pair(UtClusterInfo("FUZZER"), intRange))
                    CgMethodTestSet(
                        executableId = methodIds[testSet.method] as ExecutableId,
                        executions = testSet.executions,
                        clustersInfo = clusterInfo,
                    )
                },
                allImports
            ).generatedCode
            return testCode
        }
    }

    private fun findMethodByHeader(
        mypyStorage: MypyAnnotationStorage,
        method: PythonMethodHeader,
        curModule: String,
        sourceFileContent: String
    ): PythonMethod {
        var containingClass: CompositeType? = null
        val containingClassName = method.containingPythonClassId?.simpleName
        val functionDef = if (containingClassName == null) {
            mypyStorage.definitions[curModule]!![method.name]!!.getUtBotDefinition()!!
        } else {
            containingClass =
                mypyStorage.definitions[curModule]!![containingClassName]!!.getUtBotType() as CompositeType
            mypyStorage.definitions[curModule]!![containingClassName]!!.type.asUtBotType.getPythonAttributes().first {
                it.meta.name == method.name
            }
        } as? PythonFunctionDefinition ?: error("Selected method is not a function definition")

        val parsedFile = PythonParser(sourceFileContent).Module()
        val funcDef = PythonCode.findFunctionDefinition(parsedFile, method)

        return PythonMethod(
            name = method.name,
            moduleFilename = method.moduleFilename,
            containingPythonClass = containingClass,
            codeAsString = funcDef.body.source,
            definition = functionDef,
            ast = funcDef.body
        )
    }

    enum class MissingRequirementsActionResult {
        INSTALLED, NOT_INSTALLED
    }

    private fun getMypyReport(notEmptyTests: List<PythonTestSet>, pythonFileContent: String): List<String> =
        notEmptyTests.flatMap { testSet ->
            val lineOfFunction = getLineOfFunction(pythonFileContent, testSet.method.name)
            val msgLines = testSet.mypyReport.mapNotNull {
                if (it.file != MypyAnnotations.TEMPORARY_MYPY_FILE)
                    null
                else if (lineOfFunction != null && it.line >= 0)
                    ":${it.line + lineOfFunction}: ${it.type}: ${it.message}"
                else
                    "${it.type}: ${it.message}"
            }
            if (msgLines.isNotEmpty()) {
                listOf("MYPY REPORT (function ${testSet.method.name})") + msgLines
            } else {
                emptyList()
            }
        }

    data class InstructionSet(
        val start: Int,
        val end: Int
    )

    data class CoverageInfo(
        val covered: List<InstructionSet>,
        val notCovered: List<InstructionSet>
    )

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(CoverageInfo::class.java)

    private fun getInstructionSetList(instructions: Collection<Int>): List<InstructionSet> =
        instructions.sorted().fold(emptyList()) { acc, lineNumber ->
            if (acc.isEmpty())
                return@fold listOf(InstructionSet(lineNumber, lineNumber))
            val elem = acc.last()
            if (elem.end + 1 == lineNumber)
                acc.dropLast(1) + listOf(InstructionSet(elem.start, lineNumber))
            else
                acc + listOf(InstructionSet(lineNumber, lineNumber))
        }

    private fun getCoverageInfo(testSets: List<PythonTestSet>): String {
        val covered = mutableSetOf<Int>()
        val missed = mutableSetOf<Set<Int>>()
        testSets.forEach { testSet ->
            testSet.executions.forEach inner@{ execution ->
                val coverage = execution.coverage ?: return@inner
                coverage.coveredInstructions.forEach { covered.add(it.lineNumber) }
                missed.add(coverage.missedInstructions.map { it.lineNumber }.toSet())
            }
        }
        val coveredInstructionSets = getInstructionSetList(covered)
        val missedInstructionSets =
            if (missed.isEmpty())
                emptyList()
            else
                getInstructionSetList(missed.reduce { a, b -> a intersect b })

        return jsonAdapter.toJson(CoverageInfo(coveredInstructionSets, missedInstructionSets))
    }

    private fun relativizePaths(rootPath: Path?, paths: Set<String>): Set<String> =
        if (rootPath != null) {
            paths.map { path ->
                rootPath.relativize(Path(path)).pathString
            }.toSet()
        } else {
            paths
        }
}
