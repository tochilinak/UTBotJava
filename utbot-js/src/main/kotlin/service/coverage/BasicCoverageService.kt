package service.coverage

import java.io.File
import mu.KotlinLogging
import org.json.JSONObject
import org.utbot.framework.plugin.api.TimeoutException
import service.ServiceContext
import settings.JsTestGenerationSettings
import utils.JsCmdExec
import utils.data.ResultData

private val logger = KotlinLogging.logger {}
class BasicCoverageService(
    context: ServiceContext,
    private val scriptTexts: List<String>,
    baseCoverage: List<Int>,
) : CoverageService(context, scriptTexts, baseCoverage) {

    override fun generateCoverageReport() {
        scriptTexts.indices.forEach { index ->
            try {
                val (_, error) = JsCmdExec.runCommand(
                    cmd = arrayOf(
                        "\"${settings.pathToNode}\"",
                        "\"$utbotDirPath/${JsTestGenerationSettings.tempFileName}$index.js\""
                    ),
                    dir = projectPath,
                    shouldWait = true,
                    timeout = settings.timeout,
                )
                val resFile = File("$utbotDirPath/${JsTestGenerationSettings.tempFileName}$index.json")
                val rawResult = resFile.readText()
                resFile.delete()
                val json = JSONObject(rawResult)
                coverageList.add(index to json.getJSONObject("s"))
                val resultData = ResultData(
                    rawString = json.get("result").toString(),
                    type = json.get("type").toString(),
                    index = index,
                    isNan = json.getBoolean("is_nan"),
                    isInf = json.getBoolean("is_inf"),
                    specSign = json.getInt("spec_sign").toByte()
                )
                _resultList.add(resultData)
                val errText = error.readText()
                if (errText.isNotEmpty()) {
                    logger.error { errText }
                }
            } catch (e: TimeoutException) {
                val resultData = ResultData(
                    rawString = "Error:Timeout",
                    index = index,
                )
                coverageList.add(index to JSONObject())
                _resultList.add(resultData)
            }
        }
    }
}