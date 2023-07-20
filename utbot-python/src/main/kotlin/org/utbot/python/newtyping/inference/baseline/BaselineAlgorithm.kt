package org.utbot.python.newtyping.inference.baseline

import mu.KotlinLogging
import org.utbot.python.PythonMethod
import org.utbot.python.newtyping.*
import org.utbot.python.newtyping.ast.visitor.hints.*
import org.utbot.python.newtyping.general.FunctionType
import org.utbot.python.newtyping.general.Type
import org.utbot.python.newtyping.inference.*
import org.utbot.python.newtyping.mypy.checkSuggestedSignatureWithDMypy
import org.utbot.python.newtyping.utils.weightedRandom
import org.utbot.python.utils.TemporaryFileManager
import java.io.File
import kotlin.random.Random

private val EDGES_TO_LINK = listOf(
    EdgeSource.Identification,
    EdgeSource.Assign,
    EdgeSource.OpAssign,
    EdgeSource.Operation,
    EdgeSource.Comparison
)

private val logger = KotlinLogging.logger {}

class BaselineAlgorithm(
    private val storage: PythonTypeStorage,
    private val hintCollectorResult: HintCollectorResult,
    private val pythonPath: String,
    private val pythonMethodCopy: PythonMethod,
    private val directoriesForSysPath: Set<String>,
    private val moduleToImport: String,
    private val namesInModule: Collection<String>,
    private val initialErrorNumber: Int,
    private val configFile: File,
    private val additionalVars: String,
    private val randomTypeFrequency: Int = 0
) : TypeInferenceAlgorithm() {
    private val random = Random(0)

    private val generalRating = createGeneralTypeRating(hintCollectorResult, storage)
    private val initialState = getInitialState(hintCollectorResult, generalRating)
    private val states: MutableList<BaselineAlgorithmState> = mutableListOf(initialState)
    private val fileForMypyRuns = TemporaryFileManager.assignTemporaryFile(tag = "mypy.py")
    private var iterationCounter = 0

    private val simpleTypes = simplestTypes(storage)
    private val mixtureType = createPythonUnionType(simpleTypes)

    private val openedStates: MutableMap<Type, Pair<BaselineAlgorithmState, BaselineAlgorithmState>> = mutableMapOf()
    private val statistic: MutableMap<Type, Int> = mutableMapOf()

    fun expandState(): Type? {
        if (states.isEmpty()) return null

        logger.debug("State number: ${states.size}")
        iterationCounter++

        if (randomTypeFrequency > 0 && iterationCounter % randomTypeFrequency == 0) {
            if (iterationCounter % 2 == 0) {
                val weights = states.map { 1.0 / (it.anyNodes.size * it.anyNodes.size + 1) }
                val state = weightedRandom(states, weights, random)
                val newState = expandState(state, storage, state.anyNodes.map { mixtureType })
                if (newState != null) {
                    logger.info("Random type: ${newState.signature.pythonTypeRepresentation()}")
                    openedStates[newState.signature] = newState to state
                    return newState.signature
                }
            } else {
                val weights = statistic.map { it.value.toDouble() }
                val newType = weightedRandom(statistic.keys.toList(), weights, random)
                logger.info("Lauded type: ${newType.pythonTypeRepresentation()}")
                return newType
            }
        }

        val state = chooseState(states)
        val newState = expandState(state, storage)
        if (newState != null) {
            logger.info("Checking ${newState.signature.pythonTypeRepresentation()}")
            if (checkSignature(newState.signature as FunctionType, fileForMypyRuns, configFile)) {
                logger.debug("Found new state!")
                openedStates[newState.signature] = newState to state
                return newState.signature
            }
            expandState()
        } else {
            states.remove(state)
        }
        return null
    }

    fun feedbackState(signature: Type, feedback: InferredTypeFeedback) {
        val stateInfo = openedStates[signature]
        if (stateInfo != null) {
            val (newState, parent) = stateInfo
            when (feedback) {
                SuccessFeedback -> {
                    states.add(newState)
                    parent.children += 1
                }
                InvalidTypeFeedback -> {}
            }
            openedStates.remove(signature)
        }
    }

    override suspend fun run(
        isCancelled: () -> Boolean,
        annotationHandler: suspend (Type) -> InferredTypeFeedback,
    ): Int {

        run breaking@ {
            while (states.isNotEmpty()) {
                if (isCancelled())
                    return@breaking
                logger.debug("State number: ${states.size}")
                iterationCounter++

                if (randomTypeFrequency > 0 && iterationCounter % randomTypeFrequency == 0) {
                    val weights = states.map { 1.0 / (it.anyNodes.size * it.anyNodes.size + 1) }
                    val state = weightedRandom(states, weights, random)
                    val newState = expandState(state, storage, state.anyNodes.map { mixtureType })
                    if (newState != null) {
                        logger.info("Random type: ${newState.signature.pythonTypeRepresentation()}")
                        annotationHandler(newState.signature)
                    }
                }

                val state = chooseState(states)
                val newState = expandState(state, storage)
                if (newState != null) {
                    if (iterationCounter == 1) {
                        annotationHandler(initialState.signature)
                    }
                    logger.info("Checking ${newState.signature.pythonTypeRepresentation()}")
                    if (checkSignature(newState.signature as FunctionType, fileForMypyRuns, configFile)) {
                        logger.debug("Found new state!")
                        when (annotationHandler(newState.signature)) {
                            SuccessFeedback -> {
                                states.add(newState)
                                state.children += 1
                            }
                            InvalidTypeFeedback -> {}
                        }
                    }
                } else {
                    states.remove(state)
                }
            }
        }
        return iterationCounter
    }

    private fun checkSignature(signature: FunctionType, fileForMypyRuns: File, configFile: File): Boolean {
        pythonMethodCopy.definition = PythonFunctionDefinition(
            pythonMethodCopy.definition.meta,
            signature
        )
        return checkSuggestedSignatureWithDMypy(
            pythonMethodCopy,
            directoriesForSysPath,
            moduleToImport,
            namesInModule,
            fileForMypyRuns,
            pythonPath,
            configFile,
            initialErrorNumber,
            additionalVars
        )
    }

    private fun chooseState(states: List<BaselineAlgorithmState>): BaselineAlgorithmState {
        val weights = states.map { 1.0 / (it.children * it.children + 1)}
        return weightedRandom(states, weights, random)
    }

    private fun getInitialState(
        hintCollectorResult: HintCollectorResult,
        generalRating: List<Type>
    ): BaselineAlgorithmState {
        val paramNames = pythonMethodCopy.arguments.map { it.name }
        val root = PartialTypeNode(hintCollectorResult.initialSignature, true)
        val allNodes: MutableSet<BaselineAlgorithmNode> = mutableSetOf(root)
        val argumentRootNodes = paramNames.map { hintCollectorResult.parameterToNode[it]!! }
        argumentRootNodes.forEachIndexed { index, node ->
            val visited: MutableSet<HintCollectorNode> = mutableSetOf()
            visitNodesByReverseEdges(node, visited) { it is HintEdgeWithBound && EDGES_TO_LINK.contains(it.source) }
            val (lowerBounds, upperBounds) = collectBoundsFromComponent(visited)
            val decomposed = decompose(node.partialType, lowerBounds, upperBounds, 1, storage)
            allNodes.addAll(decomposed.nodes)
            val edge = BaselineAlgorithmEdge(
                from = decomposed.root,
                to = root,
                annotationParameterId = index
            )
            addEdge(edge)
        }
        return BaselineAlgorithmState(allNodes, generalRating, storage)
    }

    fun laudType(type: FunctionType) {
        statistic[type] = statistic[type]?.plus(1) ?: 1
    }
}