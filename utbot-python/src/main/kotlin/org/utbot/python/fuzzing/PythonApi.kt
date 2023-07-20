package org.utbot.python.fuzzing

import mu.KotlinLogging
import org.utbot.framework.plugin.api.Instruction
import org.utbot.framework.plugin.api.UtError
import org.utbot.fuzzer.FuzzedContext
import org.utbot.fuzzing.*
import org.utbot.fuzzing.utils.Trie
import org.utbot.python.framework.api.python.PythonTree
import org.utbot.python.framework.api.python.PythonUtExecution
import org.utbot.python.fuzzing.provider.*
import org.utbot.python.fuzzing.provider.utils.isAny
import org.utbot.python.newtyping.*
import org.utbot.python.newtyping.general.FunctionType
import org.utbot.python.newtyping.general.Type
import org.utbot.python.newtyping.inference.InferredTypeFeedback
import org.utbot.python.newtyping.inference.InvalidTypeFeedback
import org.utbot.python.newtyping.inference.SuccessFeedback
import org.utbot.python.newtyping.inference.baseline.BaselineAlgorithm
import org.utbot.python.utils.ExecutionWithTimeoutMode
import org.utbot.python.utils.TestGenerationLimitManager
import org.utbot.python.utils.TimeoutMode
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

data class PythonFuzzedConcreteValue(
    val type: Type,
    val value: Any,
    val fuzzedContext: FuzzedContext = FuzzedContext.Unknown,
)

class PythonMethodDescription(
    val name: String,
    parameters: List<Type>,
    val concreteValues: Collection<PythonFuzzedConcreteValue> = emptyList(),
    val pythonTypeStorage: PythonTypeStorage,
    val tracer: Trie<Instruction, *>,
    val random: Random,
    val limitManager: TestGenerationLimitManager,
    val type: FunctionType,
) : Description<Type>(parameters)

sealed interface FuzzingExecutionFeedback
class ValidExecution(val utFuzzedExecution: PythonUtExecution): FuzzingExecutionFeedback
class InvalidExecution(val utError: UtError): FuzzingExecutionFeedback
class TypeErrorFeedback(val message: String) : FuzzingExecutionFeedback
class ArgumentsTypeErrorFeedback(val message: String) : FuzzingExecutionFeedback
class CachedExecutionFeedback(val cachedFeedback: FuzzingExecutionFeedback) : FuzzingExecutionFeedback
object FakeNodeFeedback : FuzzingExecutionFeedback

data class PythonExecutionResult(
    val fuzzingExecutionFeedback: FuzzingExecutionFeedback,
    val fuzzingPlatformFeedback: PythonFeedback
)

data class PythonFeedback(
    override val control: Control = Control.CONTINUE,
    val result: Trie.Node<Instruction> = Trie.emptyNode(),
    val typeInferenceFeedback: InferredTypeFeedback = InvalidTypeFeedback,
) : Feedback<Type, PythonFuzzedValue>

class PythonFuzzedValue(
    val tree: PythonTree.PythonTreeNode,
    val summary: String? = null,
)

fun pythonDefaultValueProviders(typeStorage: PythonTypeStorage) = listOf(
    NoneValueProvider,
    BoolValueProvider,
    IntValueProvider,
    FloatValueProvider,
    ComplexValueProvider,
    StrValueProvider,
    ListValueProvider,
    SetValueProvider,
    DictValueProvider,
    TupleValueProvider,
    TupleFixSizeValueProvider,
    UnionValueProvider,
    BytesValueProvider,
    BytearrayValueProvider,
    ReduceValueProvider,
    ConstantValueProvider,
    TypeAliasValueProvider,
    SubtypeValueProvider(typeStorage)
)

fun pythonAnyTypeValueProviders() = listOf(
    NoneValueProvider,
    BoolValueProvider,
    IntValueProvider,
    FloatValueProvider,
    ComplexValueProvider,
    StrValueProvider,
    BytesValueProvider,
    BytearrayValueProvider,
    ConstantValueProvider,
)

class PythonFuzzing(
    private val pythonTypeStorage: PythonTypeStorage,
    private val typeInferenceAlgorithm: BaselineAlgorithm,
    val execute: suspend (description: PythonMethodDescription, values: List<PythonFuzzedValue>) -> PythonFeedback,
) : Fuzzing<Type, PythonFuzzedValue, PythonMethodDescription, PythonFeedback> {

    private fun generateDefault(description: PythonMethodDescription, type: Type)= sequence {
        pythonDefaultValueProviders(pythonTypeStorage).asSequence().forEach { provider ->
            if (provider.accept(type)) {
                logger.debug { "Provider ${provider.javaClass.simpleName} accepts type ${type.pythonTypeRepresentation()}" }
                yieldAll(provider.generate(description, type))
            }
        }
    }

    override fun generate(description: PythonMethodDescription, type: Type): Sequence<Seed<Type, PythonFuzzedValue>> {
        var providers = emptyList<Seed<Type, PythonFuzzedValue>>().asSequence()

        if (type.isAny()) {
            logger.debug("Any does not have provider")
        } else {
            providers += generateDefault(description, type)
        }

        return providers
    }

    override suspend fun handle(description: PythonMethodDescription, values: List<PythonFuzzedValue>): PythonFeedback {
        val result = execute(description, values)
        if (result.typeInferenceFeedback is SuccessFeedback) {
            typeInferenceAlgorithm.laudType(description.type)
        }
        if (description.limitManager.isCancelled()) {
            typeInferenceAlgorithm.feedbackState(description.type, result.typeInferenceFeedback)
        }
        return result
    }

    private suspend fun forkType(description: PythonMethodDescription, stats: Statistic<Type, PythonFuzzedValue>) {
        val type: Type? = typeInferenceAlgorithm.expandState()
        if (type != null) {
            val newTypes = (type as FunctionType).arguments
            val d = PythonMethodDescription(
                description.name,
                newTypes,
                description.concreteValues,
                description.pythonTypeStorage,
                description.tracer,
                description.random,
                TestGenerationLimitManager(ExecutionWithTimeoutMode, description.limitManager.until),
                type
            )
            logger.info { "Fork new type fuzzing" }
            if (!d.limitManager.isCancelled()) {
                fork(d, stats)
            }
            logger.info { "Fork ended" }
            description.limitManager.restart()
        } else {
            description.limitManager.mode = TimeoutMode
        }
    }

    override suspend fun isCancelled(
        description: PythonMethodDescription,
        stats: Statistic<Type, PythonFuzzedValue>
    ): Boolean {
        if (description.limitManager.isCancelled() || description.parameters.any { it.isAny() }) {
            forkType(description, stats)
        }
        return description.limitManager.isCancelled()
    }
}
