package org.utbot.cli.language.python

import java.io.File

fun String.toAbsolutePath(): String =
    File(this).canonicalPath