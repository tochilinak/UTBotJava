package org.utbot.python.newtyping

import org.utbot.python.newtyping.general.*

fun Type.isPythonType(): Boolean {
    return meta is PythonTypeDescription
}

fun Type.pythonDescription(): PythonTypeDescription {
    return meta as? PythonTypeDescription ?: error("Trying to get Python description from non-Python type $this")
}

fun Type.getPythonAttributes(): List<PythonAttribute> {
    return pythonDescription().getNamedMembers(this)
}

sealed class PythonTypeDescription(name: Name): TypeMetaDataWithName(name) {
    open fun castToCompatibleTypeApi(type: Type): Type = type
    open fun getNamedMembers(type: Type): List<PythonAttribute> = emptyList()
    open fun getAnnotationParameters(type: Type): List<Type> = emptyList()
}

sealed class PythonCompositeTypeDescription(
    name: Name,
    private val memberNames: List<String>
): PythonTypeDescription(name) {
    override fun castToCompatibleTypeApi(type: Type): CompositeType {
        return type as? CompositeType
            ?: error("Got unexpected type PythonCompositeTypeDescription: $type")
    }
    override fun getNamedMembers(type: Type): List<PythonAttribute> {
        val compositeType = castToCompatibleTypeApi(type)
        assert(compositeType.members.size == memberNames.size)
        return (memberNames zip compositeType.members).map { PythonAttribute(it.first, it.second) }
    }
    override fun getAnnotationParameters(type: Type): List<Type> = type.parameters
}

sealed class PythonSpecialAnnotation(name: Name): PythonTypeDescription(name)

class PythonTypeVarDescription(name: Name, val variance: Variance): PythonTypeDescription(name) {
    override fun castToCompatibleTypeApi(type: Type): TypeParameter {
        return type as? TypeParameter
            ?: error("Got unexpected type PythonTypeVarDescription: $type")
    }
    enum class Variance {
        INVARIANT,
        COVARIANT,
        CONTRAVARIANT
    }
}

// Composite types
class PythonConcreteCompositeTypeDescription(
    name: Name,
    memberNames: List<String>
): PythonCompositeTypeDescription(name, memberNames)
class PythonProtocolDescription(
    name: Name,
    memberNames: List<String>,
    val protocolMemberNames: List<String>
): PythonCompositeTypeDescription(name, memberNames)

class PythonCallableTypeDescription(val argumentKinds: List<ArgKind>): PythonTypeDescription(pythonCallableName) {
    override fun castToCompatibleTypeApi(type: Type): FunctionType {
        return type as? FunctionType
            ?: error("Got unexpected type PythonCallableTypeDescription: $type")
    }
    override fun getNamedMembers(type: Type): List<PythonAttribute> {
        val functionType = castToCompatibleTypeApi(type)
        return listOf(PythonAttribute("__call__", functionType))
    }
    override fun getAnnotationParameters(type: Type): List<Type> {
        val functionType = castToCompatibleTypeApi(type)
        return functionType.arguments + listOf(functionType.returnValue)
    }
    enum class ArgKind {
        Positional
    }
}

// Special Python annotations
object PythonAnyTypeDescription: PythonSpecialAnnotation(pythonAnyName)

object PythonNoneTypeDescription: PythonSpecialAnnotation(pythonNoneName) {
    override fun getNamedMembers(type: Type): List<PythonAttribute> =
        emptyList() // TODO: add None attributes
}

object PythonUnionTypeDescription: PythonSpecialAnnotation(pythonUnionName) {
    override fun castToCompatibleTypeApi(type: Type): StatefulType {
        return type as? StatefulType
            ?: error("Got unexpected type PythonUnionTypeDescription: $type")
    }
    override fun getNamedMembers(type: Type): List<PythonAttribute> {
        val statefulType = castToCompatibleTypeApi(type)
        TODO("Not yet implemented")
    }
    override fun getAnnotationParameters(type: Type): List<Type> = castToCompatibleTypeApi(type).members
}

object PythonOverloadTypeDescription: PythonSpecialAnnotation(overloadName) {
    override fun castToCompatibleTypeApi(type: Type): StatefulType {
        return type as? StatefulType
            ?: error("Got unexpected type PythonOverloadTypeDescription: $type")
    }
    override fun getAnnotationParameters(type: Type): List<Type> = castToCompatibleTypeApi(type).members
    override fun getNamedMembers(type: Type): List<PythonAttribute> {
        val statefulType = castToCompatibleTypeApi(type)
        TODO("Not yet implemented")
    }
}

object PythonTupleTypeDescription: PythonSpecialAnnotation(pythonTupleName) {
    override fun castToCompatibleTypeApi(type: Type): StatefulType {
        return type as? StatefulType
            ?: error("Got unexpected type PythonTupleTypeDescription: $type")
    }
    override fun getAnnotationParameters(type: Type): List<Type> = castToCompatibleTypeApi(type).members
    override fun getNamedMembers(type: Type): List<PythonAttribute> {
        val statefulType = castToCompatibleTypeApi(type)
        TODO("Not yet implemented")
    }
}

val pythonAnyName = Name(listOf("typing"), "Any")
val pythonUnionName = Name(listOf("typing"), "Union")
val pythonNoneName = Name(emptyList(), "None")
val pythonTupleName = Name(listOf("typing"), "Tuple")
val pythonCallableName = Name(listOf("typing"), "Callable")
val overloadName = Name(emptyList(), "Overload")

val pythonAnyType = TypeCreator.create(emptyList(), PythonAnyTypeDescription)
val pythonNoneType = TypeCreator.create(emptyList(), PythonNoneTypeDescription)

fun createPythonUnionType(members: List<Type>): StatefulType =
    StatefulTypeCreator.create(emptyList(), members, PythonUnionTypeDescription)

fun createOverloadedFunctionType(members: List<Type>): StatefulType =
    StatefulTypeCreator.create(emptyList(), members, PythonOverloadTypeDescription)

fun createPythonTupleType(members: List<Type>): StatefulType =
    StatefulTypeCreator.create(emptyList(), members, PythonTupleTypeDescription)

fun createPythonConcreteCompositeType(
    name: Name,
    numberOfParameters: Int,
    memberNames: List<String>,
    initialization: (CompositeTypeCreator.Original) -> CompositeTypeCreator.InitializationData
): CompositeType =
    CompositeTypeCreator.create(numberOfParameters, PythonConcreteCompositeTypeDescription(name, memberNames), initialization)

fun createPythonProtocol(
    name: Name,
    numberOfParameters: Int,
    memberNames: List<String>,
    protocolMemberNames: List<String>,
    initialization: (CompositeTypeCreator.Original) -> CompositeTypeCreator.InitializationData
): CompositeType =
    CompositeTypeCreator.create(numberOfParameters, PythonProtocolDescription(name, memberNames, protocolMemberNames), initialization)

fun createPythonCallableType(
    numberOfParameters: Int,
    argumentKinds: List<PythonCallableTypeDescription.ArgKind>,
    initialization: (FunctionTypeCreator.Original) -> FunctionTypeCreator.InitializationData
): FunctionType =
    FunctionTypeCreator.create(numberOfParameters, PythonCallableTypeDescription(argumentKinds), initialization)

class PythonAttribute(
    val name: String,
    val type: Type
) {
    override fun toString(): String =
        "$name: $type"
}

val exactTypeRelation = TypeRelation("=")
val upperBoundRelation = TypeRelation("<")

/*
interface PythonCompositeType: CompositeType {
    val memberNames: List<String>
    val namedMembers: List<PythonAttribute>
        get() = (memberNames zip members).map { PythonAttribute(it.first, it.second) }
    /*
    val mro: List<PythonCompositeType>
        get() {
            val result = mutableListOf(this)
            supertypes.forEach {

            }
        }
     */
}
 */