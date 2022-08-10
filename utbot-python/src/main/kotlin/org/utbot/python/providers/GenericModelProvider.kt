package org.utbot.python.providers

import org.utbot.framework.plugin.api.*
import org.utbot.fuzzer.*
import org.utbot.python.utils.*
import java.lang.Integer.min
import kotlin.random.Random

object GenericModelProvider: PythonModelProvider() {
    private val concreteTypesModelProvider = ModelProvider.of(
        ConstantModelProvider,
        DefaultValuesModelProvider,
        GenericModelProvider
    )

    private const val maxGenNum = 10

    override fun generate(description: PythonFuzzedMethodDescription): Sequence<FuzzedParameter> = sequence {
        fun <T: UtModel> fuzzGeneric(
            parameters: List<NormalizedPythonAnnotation>,
            index: Int,
            modelConstructor: (List<List<FuzzedValue>>) -> T
        ) = sequence {
            val syntheticGenericType = FuzzedMethodDescription(
                "${description.name}<syntheticGenericList>",
                pythonNoneClassId,
                parameters,
                description.concreteValues
            )
            fuzz(syntheticGenericType, concreteTypesModelProvider)
                .randomChunked()
                .map(modelConstructor)
                .forEach {
                    yield(FuzzedParameter(index, it.fuzzed()))
                }
        }

        fun genList(listAnnotation: ListAnnotation, index: Int): Sequence<FuzzedParameter> {
            return fuzzGeneric(listOf(listAnnotation.elemAnnotation), index) { list ->
                PythonListModel(
                    list.size,
                    list.flatten().map { it.model }
                )
            }
        }

        fun genDict(dictAnnotation: DictAnnotation, index: Int): Sequence<FuzzedParameter> {
            return fuzzGeneric(listOf(dictAnnotation.keyAnnotation, dictAnnotation.valueAnnotation), index) { list ->
                    PythonDictModel(
                        list.size,
                        list.associate { pair ->
                            pair[0].model to pair[1].model
                        }
                    )
                }
        }

        fun genSet(setAnnotation: SetAnnotation, index: Int): Sequence<FuzzedParameter> {
            return fuzzGeneric(listOf(setAnnotation.elemAnnotation), index) { list ->
                    PythonSetModel(
                        list.size,
                        list.flatten().map { it.model }.toSet(),
                    )
                }
        }

        description.parametersMap.forEach { (classId, parameterIndices) ->
            val parsedAnnotation = parseGeneric(classId as NormalizedPythonAnnotation) ?: return@forEach
            parameterIndices.forEach { index ->
                val generatedModels = when (parsedAnnotation) {
                    is ListAnnotation -> genList(parsedAnnotation, index)
                    is DictAnnotation -> genDict(parsedAnnotation, index)
                    is SetAnnotation -> genSet(parsedAnnotation, index)
                }
                yieldAll(
                    generatedModels.take(maxGenNum).distinctBy { fuzzedParameter ->
                        fuzzedParameter.value.model.toString()
                    }
                )
            }
        }
    }
}

const val MAX_CONTAINER_SIZE = 15

fun Sequence<List<FuzzedValue>>.randomChunked(): Sequence<List<List<FuzzedValue>>> {
    val seq = this
    val itemsToGenerateFrom = seq.take(MAX_CONTAINER_SIZE * 2).toList()
    return sequenceOf(emptyList<List<FuzzedValue>>()) + generateSequence {
        if (itemsToGenerateFrom.isEmpty())
            return@generateSequence null
        val size = Random.nextInt(1, min(MAX_CONTAINER_SIZE, itemsToGenerateFrom.size) + 1)
        (0 until size).map {
            val index = Random.nextInt(0, itemsToGenerateFrom.size)
            itemsToGenerateFrom[index]
        }
    }
}
