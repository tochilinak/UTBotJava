package org.utbot.python.evaluation

import java.io.File

object UtbotExecutorLoader {
    val packageCode = UtbotExecutorLoader::class.java.getResource("/utbot_executor.tar.gz")!!
    val version = "1.6.1.1"
//    val version = File("./utbot-python-executor/utbot_executor/pyproject.toml")
//        .readLines()
//        .first { it.startsWith("version") }
//        .split(" = ")[1]
//        .split("\"")[1]
}
