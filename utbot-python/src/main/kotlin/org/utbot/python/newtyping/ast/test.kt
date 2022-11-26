package org.utbot.python.newtyping.ast

import org.parsers.python.PythonParser
import org.parsers.python.ast.Block
import org.parsers.python.ast.FunctionDefinition
import org.utbot.python.newtyping.ast.visitor.Visitor
import org.utbot.python.newtyping.ast.visitor.hints.FunctionParameter
import org.utbot.python.newtyping.ast.visitor.hints.HintCollector
import org.utbot.python.newtyping.pythonAnyType
import org.utbot.python.newtyping.readMypyAnnotationStorage

fun main() {
    HintCollector::class.java.getResource("/annotation_sample.json")?.let { readMypyAnnotationStorage(it.readText()) }

    val content = """
        import collections

        def f(x, i):
            res = x[i:i+2:-1][0]
            res += 1
            y = res = 1 + 2
            y = [1, 2, 3, len(x), 1j, None, "123"]
            z = []
            w = [1]
            w = [len(x)]
            # x, y = res
            if i > 0 and True or (not True):
                return 1
            elif len(x) == 0:
                return 2
            else:
                for elem in x:
                    res += elem
            return res
    """.trimIndent()
    val root = PythonParser(content).Module()
    val functionBlock = root.children().first { it is FunctionDefinition }.children().first { it is Block }
    val collector = HintCollector(
        listOf(FunctionParameter("x", pythonAnyType), FunctionParameter("i", pythonAnyType))
    )
    val visitor = Visitor(listOf(collector))
    visitor.visit(functionBlock)
    val x = root.beginLine
}