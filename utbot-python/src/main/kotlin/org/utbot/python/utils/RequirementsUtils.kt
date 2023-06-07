package org.utbot.python.utils

import org.utbot.python.evaluation.UtbotExecutorLoader
import java.io.File
import java.net.URL


object RequirementsUtils {
    val requirements: List<String> = listOf(
        "mypy==1.0.0",
//        "utbot-executor==1.4.26",
        "utbot-mypy-runner==0.2.8",
    )
    private val utbotExecutorVersion = "utbot-executor==${UtbotExecutorLoader.version}"
    private val utbotExecutorCode = UtbotExecutorLoader.packageCode

    val packageRequirements: List<String> = listOf(
        utbotExecutorVersion
    )

    val allRequirements: List<String> = requirements + utbotExecutorVersion

    private val requirementsScriptContent: String =
        RequirementsUtils::class.java.getResource("/check_requirements.py")
            ?.readText()
            ?: error("Didn't find /check_requirements.py")

    fun requirementsAreInstalled(pythonPath: String): Boolean {
        return requirementsAreInstalled(pythonPath, allRequirements)
    }

    fun requirementsAreInstalled(pythonPath: String, requirementList: List<String>): Boolean {
        val requirementsScript =
            TemporaryFileManager.createTemporaryFile(requirementsScriptContent, tag = "requirements")
        val result = runCommand(
            listOf(
                pythonPath,
                requirementsScript.path
            ) + requirementList
        )
        requirementsScript.delete()
        return result.exitValue == 0
    }

    fun installRequirements(pythonPath: String): CmdResult {
        return installRequirements(pythonPath, requirements)
    }

    fun installRequirements(pythonPath: String, moduleNames: List<String>): CmdResult {
        return runCommand(
            listOf(
                pythonPath,
                "-m",
                "pip",
                "install"
            ) + moduleNames
        )
    }

    fun installPackages(pythonPath: String): CmdResult {
        return installPackages(pythonPath, listOf(utbotExecutorCode))
    }

    private fun installPackages(pythonPath: String, codeUrls: List<URL>): CmdResult {
        val packageFiles = emptyList<String>().toMutableList()
        codeUrls.forEach { codeURL ->
            val code = codeURL.readBytes()
            val packageFile = TemporaryFileManager.createTemporaryFile(code, tag = ".tar.gz")
            packageFiles.add(packageFile.absolutePath)
        }

        return installRequirements(pythonPath, packageFiles)
    }

    private fun installPackage(pythonPath: String, codeURL: URL): CmdResult {
        val code = codeURL.readBytes()
        val packageFile = TemporaryFileManager.createTemporaryFile(code)
        return installRequirements(pythonPath, listOf(packageFile.absolutePath))
    }
}
