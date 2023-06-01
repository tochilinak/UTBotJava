package org.utbot.python.utils

import org.utbot.common.asPathToFile
import java.io.IOException
import java.nio.file.Paths

object RequirementsUtils {
    val requirements: List<String> = listOf(
        "mypy==1.0.0",
//        "utbot-executor==1.4.26",
        "utbot-mypy-runner==0.2.8",
    )
    private val utbotExecutorPath =
        RequirementsUtils::class.java.getResource("/utbot-python-modules/utbot-executor")
            ?: error("Didn't find utbot-executor")

    val allRequirements: List<String> = requirements + getPathsToLocalModules()

    private val requirementsScriptContent: String =
        RequirementsUtils::class.java.getResource("/check_requirements.py")
            ?.readText()
            ?: error("Didn't find /check_requirements.py")

    fun requirementsAreInstalled(pythonPath: String): Boolean {
        return requirementsAreInstalled(pythonPath, requirements)
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

    private fun getPathsToLocalModules(): List<String> {
        return listOf(Paths.get(utbotExecutorPath.toURI()).toFile().absolutePath)
    }
}
