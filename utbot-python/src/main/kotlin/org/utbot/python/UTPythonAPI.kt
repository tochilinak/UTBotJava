package org.utbot.python

import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.UtExecution
import org.utbot.fuzzer.FuzzedConcreteValue
import java.nio.file.Path

data class PythonArgument(val name: String, val type: ClassId?)

interface PythonMethod {
    val name: String
    val returnType: ClassId?
    val arguments: List<PythonArgument>
    val sourceCodePath: Path?
    fun asString(): String
    fun getConcreteValues(): List<FuzzedConcreteValue>
}

data class PythonTestCase(
    val method: PythonMethod,
    val executions: List<UtExecution>
)