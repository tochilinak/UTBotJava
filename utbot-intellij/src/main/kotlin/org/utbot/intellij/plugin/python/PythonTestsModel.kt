package org.utbot.intellij.plugin.python

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import org.utbot.framework.codegen.TestFramework
import org.utbot.intellij.plugin.models.BaseTestsModel

class PythonTestsModel(
    project: Project,
    srcModule: Module,
    potentialTestModules: List<Module>,
    val functionsToDisplay: Set<PyFunction>,
    val containingClass: PyClass?,
    val focusedMethod: Set<PyFunction>?,
    val file: PyFile,
    val directoriesForSysPath: Set<String>,
    val currentPythonModule: String,
    var timeout: Long,
    var timeoutForRun: Long
): BaseTestsModel(
    project,
    srcModule,
    potentialTestModules
) {
     lateinit var testFramework: TestFramework
     lateinit var selectedFunctions: Set<PyFunction>
}
