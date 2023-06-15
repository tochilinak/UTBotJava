package org.utbot.python.fuzzing.provider

import org.utbot.fuzzing.Seed
import org.utbot.fuzzing.ValueProvider
import org.utbot.fuzzing.seeds.KnownValue
import org.utbot.fuzzing.seeds.RegexValue
import org.utbot.fuzzing.seeds.StringValue
import org.utbot.python.framework.api.python.PythonTree
import org.utbot.python.framework.api.python.util.pythonStrClassId
import org.utbot.python.fuzzing.PythonFuzzedConcreteValue
import org.utbot.python.fuzzing.PythonFuzzedValue
import org.utbot.python.fuzzing.PythonMethodDescription
import org.utbot.python.fuzzing.provider.utils.generateSummary
import org.utbot.python.fuzzing.provider.utils.transformQuotationMarks
import org.utbot.python.newtyping.general.Type
import org.utbot.python.newtyping.pythonTypeName
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.random.Random

object StrValueProvider : ValueProvider<Type, PythonFuzzedValue, PythonMethodDescription> {
    override fun accept(type: Type): Boolean {
        return type.pythonTypeName() == pythonStrClassId.canonicalName
    }

    private fun getStrConstants(concreteValues: Collection<PythonFuzzedConcreteValue>): List<StringValue> {
        return concreteValues
            .filter { accept(it.type) }
            .map {
                val value = it.value as String
                if (Pattern.matches("r\".*\"", value) || Pattern.matches("r'.*'", value)) {
                    value.drop(2).dropLast(1)
                } else {
                    value
                }
            }
            .map {
                StringValue(it.transformQuotationMarks())
            }
    }

    override fun generate(description: PythonMethodDescription, type: Type) = sequence {
        val strConstants = getStrConstants(description.concreteValues) + listOf(
            StringValue("pythön"),
            StringValue("abcdefghijklmnopqrst"),
            StringValue("foo"),
            StringValue("€"),
        )
        strConstants.forEach { yieldStrings(it) { value } }
        strConstants
            .map { it.value }
            .filter {
                try {
                    Pattern.compile(it); true
                } catch (_: PatternSyntaxException) {
                    false
                }
            }.forEach {
                yieldStrings(RegexValue(it, Random(0)), StringValue::value)
            }
    }

    private suspend fun <T : KnownValue<T>> SequenceScope<Seed<Type, PythonFuzzedValue>>.yieldStrings(value: T, block: T.() -> Any) {
        yield(Seed.Known(value) {
            PythonFuzzedValue(
                PythonTree.fromString(block(it).toString()),
                it.generateSummary(),
            )
        })
    }
}