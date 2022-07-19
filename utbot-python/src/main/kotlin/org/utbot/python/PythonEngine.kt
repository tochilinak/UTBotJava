package org.utbot.python

import org.utbot.framework.plugin.api.*
import org.utbot.fuzzer.FuzzedMethodDescription
import org.utbot.fuzzer.fuzz
import org.utbot.fuzzer.names.MethodBasedNameSuggester
import org.utbot.fuzzer.names.ModelBasedNameSuggester
import org.utbot.python.PythonEvaluation.evaluate
import org.utbot.python.providers.PythonModelProvider
import java.lang.Long.parseLong
import java.math.BigInteger

class PythonEngine(
    private val methodUnderTest: PythonMethod,
    private val testSourceRoot: String,
) {
    // TODO: change sequence to flow
    fun fuzzing(until: Long = Long.MAX_VALUE /*, modelProvider: (ModelProvider) -> ModelProvider = { it }*/): Sequence<UtResult> = sequence {

        val returnType = methodUnderTest.returnType
        val argumentTypes = methodUnderTest.arguments.map { it.type }

        if (returnType == null || argumentTypes.any { it == null }) {
            return@sequence
        }

        val methodUnderTestDescription = FuzzedMethodDescription(
            methodUnderTest.name,
            returnType,
            argumentTypes.map { it!! },
            methodUnderTest.getConcreteValues()
        ).apply {
            compilableName = methodUnderTest.name // what's the difference with ordinary name?
            parameterNameMap = { index -> methodUnderTest.arguments.getOrNull(index)?.name }
        }

        // model provider with fallback?
        // attempts?

        var testsGenerated = 0
        fuzz(methodUnderTestDescription, PythonModelProvider).forEach { values ->
            val modelList = values.map { it.model }

            // execute method to get function return
            // what if exception happens?
            val (resultAsString, isSuccess) = evaluate(methodUnderTest, modelList, testSourceRoot)
            val resultAsInt = BigInteger(resultAsString)// for now only int results
            val resultAsModel = UtPrimitiveModel(resultAsInt)
            val result = UtExecutionSuccess(resultAsModel)

            val nameSuggester = sequenceOf(ModelBasedNameSuggester(), MethodBasedNameSuggester())
            val testMethodName = try {
                nameSuggester.flatMap { it.suggest(methodUnderTestDescription, values, result) }.firstOrNull()
            } catch (t: Throwable) {
                null
            }

            yield(UtExecution(
                stateBefore = EnvironmentModels(null, modelList, emptyMap()),
                stateAfter = EnvironmentModels(null, modelList, emptyMap()),
                result = result,
                instrumentation = emptyList(),
                path = mutableListOf(), // ??
                fullPath = emptyList(), // ??
                testMethodName = testMethodName?.testName,
                displayName = testMethodName?.displayName
            ))

            testsGenerated += 1
            if (testsGenerated == 100)
                return@sequence


            // emit(UtExecution(/* .... */))
        }
    }
}