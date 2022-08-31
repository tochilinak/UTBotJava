package org.utbot.framework.codegen.model.constructor.tree

import org.utbot.framework.codegen.PythonImport
import org.utbot.framework.codegen.PythonUserImport
import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.constructor.util.importIfNeeded
import org.utbot.framework.codegen.model.constructor.util.plus
import org.utbot.framework.codegen.model.tree.*
import org.utbot.framework.fields.ExecutionStateAnalyzer
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.python.util.moduleOfType

internal class PythonCgMethodConstructor(context: CgContext) : CgMethodConstructor(context) {
    override fun assertEquality(expected: CgValue, actual: CgVariable) {
        pythonDeepEquals(expected, actual)
    }

    private fun generatePythonTestComments(execution: UtExecution) {
        when (execution.result) {
            is UtExplicitlyThrownException ->
                (execution.result as UtExplicitlyThrownException).exception.message?.let {
                    emptyLineIfNeeded()
                    comment("raises $it")
                }
            else -> {
                // nothing
            }
        }
    }

    override fun createTestMethod(executableId: ExecutableId, execution: UtExecution): CgTestMethod =
        withTestMethodScope(execution) {
            val testMethodName = nameGenerator.testMethodNameFor(executableId, execution.testMethodName)
            // TODO: remove this line when SAT-1273 is completed
            execution.displayName = execution.displayName?.let { "${executableId.name}: $it" }
            testMethod(testMethodName, execution.displayName) {
                val statics = currentExecution!!.stateBefore.statics
                rememberInitialStaticFields(statics)
                context.memoryObjects.clear()

                val stateAnalyzer = ExecutionStateAnalyzer(execution)
                val modificationInfo = stateAnalyzer.findModifiedFields()
                // TODO: move such methods to another class and leave only 2 public methods: remember initial and final states
                val mainBody = {
                    substituteStaticFields(statics)
                    setupInstrumentation()
                    // build this instance
                    thisInstance = execution.stateBefore.thisInstance?.let {
                        variableConstructor.getOrCreateVariable(it)
                    }
                    // build arguments
                    for ((index, param) in execution.stateBefore.parameters.withIndex()) {
                        val name = paramNames[executableId]?.get(index)
                        if (param is PythonModel) {
                            param.allContainingClassIds.forEach {
                                existingVariableNames += it.moduleName
                                importIfNeeded(it)
                            }
                        }
                        methodArguments += variableConstructor.getOrCreateVariable(param, name)
                    }
                    if (executableId is PythonMethodId) {
                        existingVariableNames += executableId.name
                        executableId.moduleName.split('.').forEach {
                            existingVariableNames += it
                        }
                    }
                    rememberInitialEnvironmentState(modificationInfo)
                    recordActualResult()
                    generateResultAssertions()
                    rememberFinalEnvironmentState(modificationInfo)
                    generateFieldStateAssertions()
                    if (executableId is PythonMethodId)
                        generatePythonTestComments(execution)
                }

                if (statics.isNotEmpty()) {
                    +tryBlock {
                        mainBody()
                    }.finally {
                        recoverStaticFields()
                    }
                } else {
                    mainBody()
                }
            }
        }

    private fun pythonBuildObject(objectNode: PythonTree.PythonTreeNode): CgValue {
        collectedImports += PythonUserImport(objectNode.type.moduleName)
        return when (objectNode) {
            is PythonTree.PrimitiveNode -> {
                CgLiteral(objectNode.type, objectNode.repr)
            }
            is PythonTree.ListNode -> {
                CgPythonList(
                    objectNode.items.map { pythonBuildObject(it) }
                )
            }
            is PythonTree.TupleNode -> {
                CgPythonTuple(
                    objectNode.items.map { pythonBuildObject(it) }
                )
            }
            is PythonTree.SetNode -> {
                CgPythonSet(
                    objectNode.items.map { pythonBuildObject(it) }.toSet()
                )
            }
            is PythonTree.DictNode -> {
                CgPythonDict(
                    objectNode.items.map { (key, value) ->
                        pythonBuildObject(key) to pythonBuildObject(value)
                    }.toMap()
                )
            }
            is PythonTree.ReduceNode -> {
                val id = objectNode.id
                if (context.memoryObjects.containsKey(id)) {
                    return context.memoryObjects[id]!!
                }
                val constructorModule = moduleOfType(objectNode.constructor) ?: objectNode.constructor
                existingVariableNames += constructorModule
                collectedImports += PythonUserImport(constructorModule)

                val initArgs = objectNode.args.map {
                    pythonBuildObject(it)
                }
                val constructor = ConstructorId(
                    PythonClassId(objectNode.constructor),
                    initArgs.map { it.type }
                )

                val obj = newVar(objectNode.type) {
                    CgConstructorCall(
                        constructor,
                        initArgs
                    )
                }
                context.memoryObjects[id] = obj

                val state = objectNode.state.map { (key, value) ->
                    key to pythonBuildObject(value)
                }.toMap()
                val listitems = objectNode.listitems.map {
                    pythonBuildObject(it)
                }
                val dictitems = objectNode.dictitems.map { (key, value) ->
                    pythonBuildObject(key) to pythonBuildObject(value)
                }

                state.forEach { (key, value) ->
                    val fieldAccess = CgFieldAccess(obj, FieldId(objectNode.type, key))
                    fieldAccess `=` value
                }
                listitems.forEach {
                    +CgMethodCall(
                        obj,
                        PythonMethodId(
                            obj.type as PythonClassId,
                            "append",
                            NormalizedPythonAnnotation(pythonNoneClassId.name),
                            listOf(RawPythonAnnotation(it.type.name))
                        ),
                        listOf(it)
                    )
                }
                dictitems.forEach { (key, value) ->
                    val index = CgPythonIndex(
                        value.type as PythonClassId,
                        obj,
                        key
                    )
                    index `=` value
                }

                return obj
            }
            else -> {
                throw UnsupportedOperationException()
            }
        }
    }

    private fun pythonDeepEquals(expected: CgValue, actual: CgVariable) {
        require(expected is CgPythonTree) {
            "Expected value have to be CgPythonTree but `${expected::class}` found"
        }
        val expectedValue = pythonBuildObject(expected.tree)
        pythonDeepTreeEquals(expected.tree, expectedValue, actual)
    }

    private fun pythonLenAssertConstructor(expected: CgVariable, actual: CgVariable): CgVariable {
        val expectedValue = newVar(pythonIntClassId, "expected_length") {
            CgGetLength(expected)
        }
        val actualValue = newVar(pythonIntClassId, "actual_length") {
            CgGetLength(actual)
        }
        emptyLineIfNeeded()
        testFrameworkManager.assertEquals(expectedValue, actualValue)
        return expectedValue
    }

    private fun pythonAssertElementsByKey(
        expectedNode: PythonTree.PythonTreeNode,
        expected: CgVariable,
        actual: CgVariable,
        iterator: CgReferenceExpression,
        keyName: String = "index",
    ) {
        val elements = when (expectedNode) {
            is PythonTree.ListNode -> expectedNode.items
            is PythonTree.TupleNode -> expectedNode.items
            is PythonTree.DictNode -> expectedNode.items.values
            else -> throw UnsupportedOperationException()
        }
        if (elements.isNotEmpty()) {
            val elementsHaveSameStructure = PythonTree.allElementsHaveSameStructure(elements)
            val firstChild =
                elements.first()  // TODO: We can use only structure => we should use another element if the first is empty

            emptyLine()
            if (elementsHaveSameStructure) {
                val index = newVar(pythonNoneClassId, keyName) {
                    CgLiteral(pythonNoneClassId, "None")
                }
                forEachLoop {
                    innerBlock {
                        condition = index
                        iterable = iterator
                        val indexExpected = newVar(firstChild.type, "expected_element") {
                            CgPythonIndex(
                                pythonIntClassId,
                                expected,
                                index
                            )
                        }
                        val indexActual = newVar(firstChild.type, "actual_element") {
                            CgPythonIndex(
                                pythonIntClassId,
                                actual,
                                index
                            )
                        }
                        pythonDeepTreeEquals(firstChild, indexExpected, indexActual)
                        statements = currentBlock
                    }
                }
            }
            else {
                emptyLineIfNeeded()
                testFrameworkManager.assertIsinstance(listOf(expected.type), actual)
            }
        }
    }

    private fun pythonAssertBuiltinsCollection(
        expectedNode: PythonTree.PythonTreeNode,
        expected: CgValue,
        actual: CgVariable,
        expectedName: String,
        elementName: String = "index",
    ) {
        val expectedCollection = newVar(expected.type, expectedName) { expected }

        val length = pythonLenAssertConstructor(expectedCollection, actual)

        val iterator = if (expectedNode is PythonTree.DictNode) expected else CgPythonRange(length)
        pythonAssertElementsByKey(expectedNode, expectedCollection, actual, iterator, elementName)
    }

    private fun pythonDeepTreeEquals(
        expectedNode: PythonTree.PythonTreeNode,
        expected: CgValue,
        actual: CgVariable
    ) {
        if (expectedNode.comparable) {
            emptyLineIfNeeded()
            testFrameworkManager.assertEquals(
                expected,
                actual,
            )
            return
        }
        when (expectedNode) {
            is PythonTree.PrimitiveNode -> {
                emptyLineIfNeeded()
                testFrameworkManager.assertIsinstance(
                    listOf(expected.type), actual
                )
            }
            is PythonTree.ListNode -> {
                pythonAssertBuiltinsCollection(
                    expectedNode,
                    expected,
                    actual,
                    "expected_list"
                )
            }
            is PythonTree.TupleNode -> {
                pythonAssertBuiltinsCollection(
                    expectedNode,
                    expected,
                    actual,
                    "expected_tuple"
                )
            }
            is PythonTree.SetNode -> {
                emptyLineIfNeeded()
                testFrameworkManager.assertEquals(
                    expected, actual
                )
            }
            is PythonTree.DictNode -> {
                pythonAssertBuiltinsCollection(
                    expectedNode,
                    expected,
                    actual,
                    "expected_dict",
                    "key"
                )
            }
            is PythonTree.ReduceNode -> {
                if (expectedNode.state.isNotEmpty()) {
                    expectedNode.state.forEach { (field, value) ->
                        val fieldActual = newVar(value.type, "actual_$field") {
                            CgFieldAccess(
                                actual, FieldId(
                                    value.type,
                                    field
                                )
                            )
                        }
                        val fieldExpected = newVar(value.type, "expected_$field") {
                            CgFieldAccess(
                                expected, FieldId(
                                    value.type,
                                    field
                                )
                            )
                        }
                        pythonDeepTreeEquals(value, fieldExpected, fieldActual)
                    }
                } else {
                    emptyLineIfNeeded()
                    testFrameworkManager.assertIsinstance(
                        listOf(expected.type), actual
                    )
                }
            }
            else -> {}
        }
    }
}