package org.utbot.python.typing

import com.beust.klaxon.Klaxon
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.RegexFileFilter
import org.utbot.framework.plugin.api.PythonClassId
import org.utbot.python.code.ClassInfoCollector
import org.utbot.python.code.PythonClass
import org.utbot.python.code.PythonCode
import org.utbot.python.utils.AnnotationNormalizer.annotationFromProjectToClassId
import org.utbot.python.utils.AnnotationNormalizer.annotationFromStubToClassId
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

class PythonType(
    val name: String, // include module name (like 'ast.Assign')
    val initSignature: List<PythonClassId>?,
    val sourceFile: String?,
    val preprocessedInstances: List<String>?,
    val methods: List<String>,
    val fields: List<String>,
    val returnRenderType: ReturnRenderType = ReturnRenderType.REPR
) {
    val module: String?
        get() = moduleOfType(name)
}

fun moduleOfType(typeName: String): String? {
    val lastIndex = typeName.lastIndexOf('.')
    return if (lastIndex == -1) null else typeName.substring(0, lastIndex)
}

enum class ReturnRenderType {
    REPR, PICKLE, NONE
}

object PythonTypesStorage {
    private var projectClasses: List<ProjectClass> = emptyList()
    var pythonPath: String? = null
    private const val noPythonMsg = "PythonPath in PythonTypeCollector not specified"

    private fun mapToClassId(typesFromStubs: Collection<StubFileFinder.SearchResult>): List<PythonClassId> =
        typesFromStubs.map {
            annotationFromStubToClassId(it.typeName, pythonPath ?: error(noPythonMsg), it.module)
        }

    fun normalizeAnnotation(annotation: String): String {
        val module = moduleOfType(annotation) ?: return annotation
        return annotationFromStubToClassId(annotation, pythonPath ?: error(noPythonMsg), module).name
    }

    fun findTypeWithMethod(
        methodName: String
    ): Set<PythonClassId> {
        val fromStubs = mapToClassId(StubFileFinder.findTypeWithMethod(methodName))
        val fromProject = projectClasses.mapNotNull {
            if (it.info.methods.contains(methodName)) PythonClassId(it.pythonClass.name) else null
        }
        return (fromStubs union fromProject).toSet()
    }

    fun findTypeWithField(
        fieldName: String
    ): Set<ClassId> {
        val fromStubs = mapToClassId(StubFileFinder.findTypeWithField(fieldName))
        val fromProject = projectClasses.mapNotNull {
            if (it.info.fields.contains(fieldName)) PythonClassId(it.pythonClass.name) else null
        }
        return (fromStubs union fromProject).toSet()
    }

    fun findTypeByFunctionWithArgumentPosition(
        functionName: String,
        argumentName: String? = null,
        argumentPosition: Int? = null,
    ): Set<PythonClassId> =
        mapToClassId(
            StubFileFinder.findAnnotationByFunctionWithArgumentPosition(functionName, argumentName, argumentPosition)
        ).toSet()

    fun findTypeByFunctionReturnValue(functionName: String): Set<PythonClassId> =
        mapToClassId(StubFileFinder.findAnnotationByFunctionReturnValue(functionName)).toSet()

    fun isFromProject(typeName: String): Boolean {
        return projectClasses.any { it.pythonClass.name == typeName }
    }

    fun getTypeByName(classId: PythonClassId): PythonType? {
        val fromStub = StubFileFinder.nameToClassMap[classId.name]
        val result =
            if (fromStub != null) {
                val fromPreprocessed = TypesFromJSONStorage.typeNameMap[classId.name]
                return PythonType(
                    classId.name,
                    fromStub.methods.find { it.name == "__init__" }
                        ?.args
                        ?.drop(1) // drop 'self' parameter
                        ?.map { annotationFromStubToClassId(
                            it.annotation,
                            pythonPath ?: error(noPythonMsg),
                            moduleOfType(classId.name) ?: "builtins"
                        ) },
                    null,
                    fromPreprocessed?.instances,
                    fromStub.methods.map { it.name },
                    fromStub.fields.map { it.name },
                    if (fromPreprocessed?.useAsReturn == false) ReturnRenderType.NONE else ReturnRenderType.REPR
                )
            } else {
                projectClasses.find { it.pythonClass.name == classId.name } ?.let { projectClass ->
                    PythonType(
                        classId.name,
                        projectClass.initAnnotation,
                        projectClass.pythonClass.filename,
                        null,
                        projectClass.info.methods,
                        projectClass.info.fields
                    )
                }
            }

        return result
    }

    val builtinTypes: List<String>
        get() = TypesFromJSONStorage.preprocessedTypes.mapNotNull {
            if (it.name.startsWith("builtins.")) it.name.removePrefix("builtins.") else null
        }

    private data class ProjectClass(
        val pythonClass: PythonClass,
        val info: ClassInfoCollector.Storage,
        val initAnnotation: List<PythonClassId>?
    )

    private fun getPythonFiles(dirPath: String): Collection<File> =
        FileUtils.listFiles(
            File(dirPath),
            RegexFileFilter("^.*[.]py"),
            DirectoryFileFilter.DIRECTORY
        )

    fun refreshProjectClassesList(
        path: String,
        projectRoot: String,
        directoriesForSysPath: List<String>
    ) {
        val pythonFiles = if (path.endsWith(".py")) listOf(File(path)) else getPythonFiles(path)
        projectClasses = pythonFiles.flatMap { file ->
            val content = IOUtils.toString(FileInputStream(file), StandardCharsets.UTF_8)
            val code = PythonCode.getFromString(content, file.path)
            code.getToplevelClasses().map { pyClass ->
                val collector = ClassInfoCollector(pyClass)
                val initSignature = pyClass.initSignature
                    ?.map {
                        annotationFromProjectToClassId(
                            it.annotation,
                            pythonPath ?: error("PythonPath in PythonTypeCollector not specified"),
                            projectRoot,
                            pyClass.filename!!,
                            directoriesForSysPath
                        )
                    }
                ProjectClass(pyClass, collector.storage, initSignature)
            }
        }
    }

    private data class PreprocessedValueFromJSON(
        val name: String,
        val instances: List<String>,
        val useAsReturn: Boolean
    )

    private object TypesFromJSONStorage {
        val preprocessedTypes: List<PreprocessedValueFromJSON>
        init {
            val typesAsString = PythonTypesStorage::class.java.getResource("/preprocessed_values.json")
                ?.readText(Charsets.UTF_8)
                ?: error("Didn't find preprocessed_values.json")
            preprocessedTypes =  Klaxon().parseArray(typesAsString) ?: emptyList()
        }

        val typeNameMap: Map<String, PreprocessedValueFromJSON> by lazy {
            val result = mutableMapOf<String, PreprocessedValueFromJSON>()
            preprocessedTypes.forEach { type ->
                result[type.name] = type
            }
            result
        }
    }
}