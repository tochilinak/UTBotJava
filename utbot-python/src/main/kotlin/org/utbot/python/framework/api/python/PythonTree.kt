package org.utbot.python.framework.api.python

import org.utbot.python.framework.api.python.util.pythonBoolClassId
import org.utbot.python.framework.api.python.util.pythonFloatClassId
import org.utbot.python.framework.api.python.util.pythonIntClassId
import org.utbot.python.framework.api.python.util.pythonNoneClassId
import org.utbot.python.framework.api.python.util.pythonStrClassId
import org.utbot.python.framework.api.python.util.toPythonRepr
import org.utbot.python.newtyping.general.Type
import org.utbot.python.newtyping.pythonTypeName
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import java.util.concurrent.atomic.AtomicLong


object PythonTree {
    open class PythonTreeNode(
        val id: Long,
        val type: PythonClassId,
        var comparable: Boolean = true,
    ) {
        constructor(type: PythonClassId, comparable: Boolean = true) : this(PythonIdGenerator.createId(), type, comparable)

        open val children: List<PythonTreeNode> = emptyList()

        override fun toString(): String {
            return type.name + children.toString()
        }

        open fun typeEquals(other: Any?): Boolean {
            return if (other is PythonTreeNode)
                type == other.type && comparable && other.comparable
            else
                false
        }

        override fun equals(other: Any?): Boolean {
            if (other !is PythonTreeNode) {
                return false
            }
            return type == other.type && children == other.children && comparable == other.comparable
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + comparable.hashCode()
            result = 31 * result + children.hashCode()
            return result
        }

        open fun softEquals(other: PythonTreeNode): Boolean {  // overridden for ReduceNode
            return this == other
        }

        open fun softHashCode(): Int {  // overridden for ReduceNode
            return hashCode()
        }

        open fun diversity(): Int = 1 + children.fold(0) { acc, child -> acc + child.diversity() }
    }

    class PrimitiveNode(
        id: Long,
        type: PythonClassId,
        val repr: String,
    ) : PythonTreeNode(id, type) {
        constructor(type: PythonClassId, repr: String) : this(PythonIdGenerator.getOrCreateIdForValue(repr), type, repr)
        override fun equals(other: Any?): Boolean {
            if (other !is PrimitiveNode) {
                return false
            }
            return repr == other.repr && type == other.type
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + repr.hashCode()
            return result
        }

        override fun toString(): String {
            return repr
        }

        override fun diversity(): Int = 2
    }

    class ListNode(
        id: Long,
        val items: MutableMap<Int, PythonTreeNode>
    ) : PythonTreeNode(id, PythonClassId("builtins.list")) {
        constructor(items: MutableMap<Int, PythonTreeNode>) : this(PythonIdGenerator.createId(), items)

        override val children: List<PythonTreeNode>
            get() = items.values.toList()

        override fun typeEquals(other: Any?): Boolean {
            return if (other is ListNode)
                children.zip(other.children).all {
                    it.first.typeEquals(it.second)
                }
            else false
        }

        override fun equals(other: Any?): Boolean {
            if (other !is ListNode) {
                return false
            }
            return children == other.children
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + items.hashCode()
            return result
        }
    }

    class DictNode(
        id: Long,
        val items: MutableMap<PythonTreeNode, PythonTreeNode>
    ) : PythonTreeNode(id, PythonClassId("builtins.dict")) {
        constructor(items: MutableMap<PythonTreeNode, PythonTreeNode>) : this(PythonIdGenerator.createId(), items)

        override val children: List<PythonTreeNode>
            get() = items.values + items.keys

        override fun typeEquals(other: Any?): Boolean {
            return if (other is DictNode) {
                items.keys.size == other.items.keys.size && items.keys.all {
                    items[it]?.typeEquals(other.items[it]) ?: false
                }

            } else false
        }

        override fun equals(other: Any?): Boolean {
            if (other !is DictNode) {
                return false
            }
            return children == other.children
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + items.hashCode()
            return result
        }

    }

    class SetNode(
        id: Long,
        val items: MutableSet<PythonTreeNode>
    ) : PythonTreeNode(id, PythonClassId("builtins.set")) {
        constructor(items: MutableSet<PythonTreeNode>) : this(PythonIdGenerator.createId(), items)

        override val children: List<PythonTreeNode>
            get() = items.toList()

        override fun typeEquals(other: Any?): Boolean {
            return if (other is SetNode) {
                items.size == other.items.size && (
                        items.isEmpty() || items.all {
                            items.first().typeEquals(it)
                        } && other.items.all {
                            items.first().typeEquals(it)
                        })
            } else {
                false
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is SetNode) {
                return false
            }
            return items == other.items
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + items.hashCode()
            return result
        }

    }

    class TupleNode(
        id: Long,
        val items: MutableMap<Int, PythonTreeNode>
    ) : PythonTreeNode(id, PythonClassId("builtins.tuple")) {
        constructor(items: MutableMap<Int, PythonTreeNode>) : this(PythonIdGenerator.createId(), items)

        override val children: List<PythonTreeNode>
            get() = items.values.toList()

        override fun typeEquals(other: Any?): Boolean {
            return if (other is TupleNode) {
                items.size == other.items.size && children.zip(other.children).all {
                    it.first.typeEquals(it.second)
                }
            } else {
                false
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is TupleNode) {
                return false
            }
            return items == other.items
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + items.hashCode()
            return result
        }
    }

    class ReduceNode(
        id: Long,
        type: PythonClassId,
        val constructor: PythonClassId,
        val args: List<PythonTreeNode>,
        var state: MutableMap<String, PythonTreeNode>,
        var listitems: List<PythonTreeNode>,
        var dictitems: Map<PythonTreeNode, PythonTreeNode>,
    ) : PythonTreeNode(id, type) {
        constructor(
            type: PythonClassId,
            constructor: PythonClassId,
            args: List<PythonTreeNode>,
        ) : this(PythonIdGenerator.createId(), type, constructor, args, emptyMap<String, PythonTreeNode>().toMutableMap(), emptyList(), emptyMap())

        override val children: List<PythonTreeNode>
            get() = args + state.values + listitems + dictitems.values + dictitems.keys + PythonTreeNode(constructor)

        override fun typeEquals(other: Any?): Boolean {
            return if (other is ReduceNode) {
                type == other.type && state.all { (key, value) ->
                    other.state.containsKey(key) && value.typeEquals(other.state[key])
                } && listitems.withIndex().all { (index, item) ->
                    other.listitems.size > index && item.typeEquals(other.listitems[index])
                } && dictitems.all { (key, value) ->
                    other.dictitems.containsKey(key) && value.typeEquals(other.dictitems[key])
                }
            } else false
        }

        override fun equals(other: Any?): Boolean {
            if (other !is ReduceNode) {
                return false
            }
            return type == other.type &&
                    id == other.id &&
                    constructor == other.constructor &&
                    args == other.args &&
                    state == other.state &&
                    listitems == other.listitems &&
                    dictitems == other.dictitems
        }

        override fun hashCode(): Int {
            var result = softHashCode()
            result = 31 * result + id.hashCode()
            return result
        }

        override fun softEquals(other: PythonTreeNode): Boolean {  // like equals(), but skip id check
            if (other !is ReduceNode)
                return false
            return type == other.type && constructor == other.constructor && args == other.args &&
                    state == other.state && listitems == other.listitems && dictitems == other.dictitems
        }

        override fun softHashCode(): Int {
            var result = constructor.hashCode()
            result = 31 * result + args.hashCode()
            result = 31 * result + state.hashCode()
            result = 31 * result + listitems.hashCode()
            result = 31 * result + dictitems.hashCode()
            return result
        }
    }

    fun allElementsHaveSameStructure(elements: Collection<PythonTreeNode>): Boolean {
        return if (elements.isEmpty()) {
            true
        } else {
            val firstElement = elements.first()
            elements.drop(1).all {
                it.typeEquals(firstElement)
            }
        }
    }

    fun fromObject(): PrimitiveNode {
        return PrimitiveNode(
            PythonClassId("builtins.object"),
            "object()"
        )
    }

    fun fromNone(): PrimitiveNode {
        return PrimitiveNode(
            pythonNoneClassId,
            "None"
        )
    }

    fun fromInt(value: BigInteger): PrimitiveNode {
        return PrimitiveNode(
            pythonIntClassId,
            value.toString(10)
        )
    }

    fun fromString(value: String): PrimitiveNode {
        return PrimitiveNode(
            pythonStrClassId,
            value.toPythonRepr()
        )
    }

    fun fromBool(value: Boolean): PrimitiveNode {
        return PrimitiveNode(
            pythonBoolClassId,
            if (value) "True" else "False"
        )
    }

    fun fromFloat(value: Double): PrimitiveNode {
        return PrimitiveNode(
            pythonFloatClassId,
            value.toString()
        )
    }

    fun fromParsedConstant(value: Pair<Type, Any>): PythonTreeNode? {
        return when (value.first.pythonTypeName()) {
            "builtins.int" -> fromInt(value.second as? BigInteger ?: return null)
            "builtins.float" -> fromFloat((value.second as? BigDecimal)?.toDouble() ?: return null)
            "typing.Tuple", "builtins.tuple" -> {
                val elemsUntyped = (value.second as? List<*>) ?: return null
                val elems = elemsUntyped.map {
                    val pair = it as? Pair<*,*> ?: return null
                    Pair(pair.first as? Type ?: return null, pair.second ?: return null)
                }
                TupleNode(
                    elems.mapIndexed { index, pair ->
                        Pair(index, fromParsedConstant(pair) ?: return null)
                    }.associate { it }.toMutableMap()
                )
            }
            else -> null
        }
    }
}

object PythonIdGenerator {
    private const val lower_bound: Long = 1500_000_000

    private val lastId: AtomicLong = AtomicLong(lower_bound)
    private val cache: IdentityHashMap<Any?, Long> = IdentityHashMap()

    fun getOrCreateIdForValue(value: Any): Long {
        return cache.getOrPut(value) { createId() }
    }

    fun createId(): Long {
        return lastId.incrementAndGet()
    }

}

class PythonTreeWrapper(val tree: PythonTree.PythonTreeNode) {
    override fun equals(other: Any?): Boolean {
        if (other !is PythonTreeWrapper)
            return false
        return tree.softEquals(other.tree)
    }

    override fun hashCode(): Int {
        return tree.softHashCode()
    }
}