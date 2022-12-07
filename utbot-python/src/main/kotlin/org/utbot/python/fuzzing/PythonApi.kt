package org.utbot.python.fuzzing

import org.utbot.fuzzer.FuzzedContext
import org.utbot.fuzzing.Control
import org.utbot.fuzzing.Description
import org.utbot.fuzzing.Feedback
import org.utbot.fuzzing.Fuzzing
import org.utbot.fuzzing.Routine
import org.utbot.fuzzing.Seed
import org.utbot.fuzzing.seeds.BitVectorValue
import org.utbot.fuzzing.seeds.Bool
import org.utbot.fuzzing.seeds.KnownValue
import org.utbot.fuzzing.seeds.Signed
import org.utbot.fuzzing.seeds.StringValue
import org.utbot.python.framework.api.python.PythonClassId
import org.utbot.python.framework.api.python.PythonTree
import org.utbot.python.framework.api.python.PythonTreeModel
import org.utbot.python.framework.api.python.util.pythonBoolClassId
import org.utbot.python.framework.api.python.util.pythonFloatClassId
import org.utbot.python.framework.api.python.util.pythonIntClassId
import org.utbot.python.framework.api.python.util.pythonListClassId
import org.utbot.python.framework.api.python.util.pythonNoneClassId
import org.utbot.python.framework.api.python.util.pythonStrClassId
import org.utbot.python.framework.api.python.util.pythonTupleClassId
import org.utbot.python.newtyping.PythonAnyTypeDescription
import org.utbot.python.newtyping.PythonCompositeTypeDescription
import org.utbot.python.newtyping.PythonConcreteCompositeTypeDescription
import org.utbot.python.newtyping.PythonNoneTypeDescription
import org.utbot.python.newtyping.PythonOverloadTypeDescription
import org.utbot.python.newtyping.PythonProtocolDescription
import org.utbot.python.newtyping.PythonSpecialAnnotation
import org.utbot.python.newtyping.PythonTupleTypeDescription
import org.utbot.python.newtyping.PythonUnionTypeDescription
import org.utbot.python.newtyping.general.Type

data class PythonFuzzedConcreteValue(
    val classId: Type,
    val value: Any,
    val fuzzedContext: FuzzedContext = FuzzedContext.Unknown,
)

class PythonMethodDescriptionNew(
    val name: String,
    parameters: List<Type>,
    val concreteValues: Collection<PythonFuzzedConcreteValue> = emptyList()
) : Description<Type>(parameters)

class PythonFeedbackNew(
    override val control: Control = Control.CONTINUE
) : Feedback<Type, PythonTreeModel> {
    override fun equals(other: Any?): Boolean {
        val castOther = other as? PythonFeedbackNew
        return control == castOther?.control
    }

    override fun hashCode(): Int {
        return control.hashCode()
    }
}

class PythonFuzzing(
    val execute: suspend (description: PythonMethodDescriptionNew, values: List<PythonTreeModel>) -> PythonFeedbackNew
) : Fuzzing<Type, PythonTreeModel, PythonMethodDescriptionNew, PythonFeedbackNew> {
    override fun generate(description: PythonMethodDescriptionNew, type: Type): Sequence<Seed<Type, PythonTreeModel>> = sequence {
        val meta = type.meta
        when (meta) {
            is PythonCompositeTypeDescription -> {
                when (meta) {
                    is PythonConcreteCompositeTypeDescription -> {
                        val annotation = meta.name
                        when (annotation.toString()) {
                            "builtins.int" -> {
                                val integerConstants = listOf(
                                    BitVectorValue.fromInt(0),
                                    BitVectorValue.fromInt(1),
                                    BitVectorValue.fromInt(-1),
                                    BitVectorValue.fromInt(101),
                                    BitVectorValue.fromInt(-101),
                                ).asSequence()
                                yieldIntegers(128, integerConstants) { toBigInteger().toString(10) }
                            }

                            "builtins.bool" -> {
                                yieldBool(Bool.TRUE()) { true }
                                yieldBool(Bool.FALSE()) { false }
                            }

                            "builtins.str" -> {
                                yieldStrings(StringValue("\"\"")) { value }
                                yieldStrings(StringValue("\"abc\"")) { value }
                            }

                            "builtins.float" -> {
                                yield(Seed.Simple(PythonTreeModel(PythonTree.fromFloat(1.1), pythonFloatClassId)))
                                yield(Seed.Simple(PythonTreeModel(PythonTree.fromFloat(1.0), pythonFloatClassId)))
                                yield(Seed.Simple(PythonTreeModel(PythonTree.fromFloat(1.6), pythonFloatClassId)))
                                yield(Seed.Simple(PythonTreeModel(PythonTree.fromFloat(-1.6), pythonFloatClassId)))
                                yield(Seed.Simple(PythonTreeModel(PythonTree.fromFloat(-1.0), pythonFloatClassId)))
                                yield(Seed.Simple(PythonTreeModel(PythonTree.fromFloat(0.0), pythonFloatClassId)))
                            }
                            "builtins.list" -> {
                                val param = meta.getAnnotationParameters(type)
                                yield(
                                    Seed.Collection(
                                        construct = Routine.Collection {
                                            PythonTreeModel(
                                                PythonTree.ListNode(
                                                    emptyMap<Int, PythonTree.PythonTreeNode>().toMutableMap(),
                                                ),
                                                pythonListClassId
                                            )
                                        },
                                        modify = Routine.ForEach(param) { self, i, values ->
                                            (self.tree as PythonTree.ListNode).items[i] = values.first().tree
                                        }
                                    ))
                            }
                            "builtins.tuple" -> {
                                val param = meta.getAnnotationParameters(type)
                                yield(
                                    Seed.Collection(
                                        construct = Routine.Collection {
                                            PythonTreeModel(
                                                PythonTree.TupleNode(
                                                    emptyMap<Int, PythonTree.PythonTreeNode>().toMutableMap(),
                                                ),
                                                pythonTupleClassId
                                            )
                                        },
                                        modify = Routine.ForEach(param) { self, i, values ->
                                            (self.tree as PythonTree.TupleNode).items[i] = values.first().tree
                                        }
                                    ))
                            }
//                            "builtins.dict" -> {
//                                val params = meta.getAnnotationParameters(type)
//                                yield(
//                                    Value.Recursive(
//                                        construct = Routine.Create(params) { v ->
//                                            PythonTreeModel(
//                                                PythonTree.DictNode(v.withIndex().associate { it.index to it.value.tree }.toMutableMap()),
//                                                pythonTupleClassId
//                                            )
//                                        },
//                                    )
//                                )
//                            }
                        }
                    }
                    is PythonProtocolDescription -> TODO()
                }
            }
            is PythonSpecialAnnotation -> {
                when (meta) {
                    is PythonNoneTypeDescription -> {
                        yield(Seed.Simple(PythonTreeModel(PythonTree.fromNone(), pythonNoneClassId)))
                    }
                    PythonAnyTypeDescription -> TODO()
                    PythonOverloadTypeDescription -> TODO()
                    PythonUnionTypeDescription -> {
                        val params = meta.getAnnotationParameters(type)
                        params.forEach { unionParam ->
                            yield(Seed.Recursive(
                                construct = Routine.Create(listOf(unionParam)) { v -> v.first() },
                                empty = Routine.Empty { PythonTreeModel(PythonTree.fromNone(), PythonClassId(meta.name.toString())) }
                            ))
                        }
                    }
                    PythonTupleTypeDescription -> {
                        val params = meta.getAnnotationParameters(type)
                        val length = params.size
                        val modifications = emptyList<Routine.Call<Type, PythonTreeModel>>().toMutableList()
                        for (i in 0 until length) {
                            modifications.add(Routine.Call(listOf(params[i])) { instance, arguments ->
                                (instance.tree as PythonTree.TupleNode).items[i] = arguments.first().tree
                            })
                        }
                        yield(Seed.Recursive(
                            construct = Routine.Create(params) { v ->
                                PythonTreeModel(
                                    PythonTree.TupleNode(v.withIndex().associate { it.index to it.value.tree }.toMutableMap()),
                                    pythonTupleClassId
                                )
                            },
                            modify = modifications.asSequence(),
                            empty = Routine.Empty { PythonTreeModel(
                                PythonTree.TupleNode(emptyMap<Int, PythonTree.PythonTreeNode>().toMutableMap()),
                                pythonTupleClassId
                            )}
                        ))
                    }
                }
            }
        }
    }

    private suspend fun SequenceScope<Seed<Type, PythonTreeModel>>.yieldIntegers(
        bits: Int,
        consts: Sequence<BitVectorValue> = emptySequence(),
        block: BitVectorValue.() -> String,
    ) {
        (consts.filter { it.size <= bits } + Signed.values().map { it.invoke(bits) }).forEach { vector ->
            yield(Seed.Known(vector) { PythonTreeModel(PythonTree.PrimitiveNode(pythonIntClassId, block(it)), pythonIntClassId) })
        }
    }

    private suspend fun <T : KnownValue> SequenceScope<Seed<Type, PythonTreeModel>>.yieldBool(value: T, block: T.() -> Boolean) {
        yield(Seed.Known(value) { PythonTreeModel(PythonTree.fromBool(block(it)), pythonBoolClassId) })
    }

    private suspend fun <T : KnownValue> SequenceScope<Seed<Type, PythonTreeModel>>.yieldStrings(value: T, block: T.() -> Any) {
        yield(Seed.Known(value) { PythonTreeModel(PythonTree.fromString(block(it).toString()), pythonStrClassId) })
    }

    override suspend fun run(description: PythonMethodDescriptionNew, values: List<PythonTreeModel>): PythonFeedbackNew {
        return execute(description, values)
    }
}