package org.utbot.python.utils.mypy

import org.utbot.python.newtyping.mypy.MypyAnnotationStorage
import org.utbot.python.newtyping.mypy.MypyReportLine
import java.io.File

data class MypyConfig (
    val mypyStorage: MypyAnnotationStorage,
    val mypyReportLine: List<MypyReportLine>,
    val mypyConfigFile: File,
)