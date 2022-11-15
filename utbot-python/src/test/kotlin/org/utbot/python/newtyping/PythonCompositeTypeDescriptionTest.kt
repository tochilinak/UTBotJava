package org.utbot.python.newtyping

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PythonCompositeTypeDescriptionTest {
    lateinit var storage: MypyAnnotationStorage
    @BeforeAll
    fun setup() {
        val sample = AnnotationFromMypyKtTest::class.java.getResource("/annotation_sample.json")!!.readText()
        storage = readMypyAnnotationStorage(sample)
    }

    @Test
    fun testMroForCounter() {
        val counter = storage.definitions["collections"]!!["Counter"]!!.annotation.asUtBotType
        val counterDescription = counter.pythonDescription() as PythonCompositeTypeDescription
        assertTrue(
            counterDescription.mro(counter).map { it.pythonDescription().name.name } == listOf(
                "Counter", "dict", "MutableMapping", "Mapping", "Collection", "Iterable", "Container", "object"
            )
        )
        val dict = counterDescription.mro(counter).find { it.pythonDescription().name.name == "dict" }!!
        assertTrue(dict.parameters.size == 2)
        assertTrue(dict.parameters[0] == counter.parameters[0])
        assertTrue(dict.parameters[1].pythonDescription().name.name == "int")

        val mapping = counterDescription.mro(counter).find { it.pythonDescription().name.name == "Mapping" }!!
        assertTrue(mapping.parameters.size == 2)
        assertTrue(mapping.parameters[0] == counter.parameters[0])
        assertTrue(mapping.parameters[1].pythonDescription().name.name == "int")
    }

    @Test
    fun testMroForObject() {
        val obj = storage.definitions["builtins"]!!["object"]!!.annotation.asUtBotType
        val description = obj.pythonDescription() as PythonCompositeTypeDescription
        assertTrue(
            description.mro(obj).map { it.pythonDescription().name.name } == listOf("object")
        )
    }

    @Test
    fun testMroForDeque() {
        val deque = storage.definitions["collections"]!!["deque"]!!.annotation.asUtBotType
        val description = deque.pythonDescription() as PythonCompositeTypeDescription
        assertTrue(
            description.mro(deque).map { it.pythonDescription().name.name } == listOf(
                "deque", "MutableSequence", "Sequence", "Collection", "Reversible", "Iterable", "Container", "object"
            )
        )
        val iterable = description.mro(deque).find { it.pythonDescription().name.name == "Iterable" }!!
        assertTrue(deque.parameters.size == 1)
        assertTrue(iterable.parameters == deque.parameters)
    }
}