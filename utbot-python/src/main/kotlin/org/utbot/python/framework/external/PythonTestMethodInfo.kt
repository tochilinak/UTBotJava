package org.utbot.python.framework.external

class PythonTestMethodInfo(
    val methodName: PythonObjectName,
    val moduleFilename: String,
    val containingClassName: PythonObjectName? = null
) {
}