package org.utbot.python.utils

import org.utbot.python.PythonMethod

object StatisticCollector {
    var timeShift = 0
    val timeSlots = mutableMapOf<Int, TimeSlot>()

    fun addStatistic(time: Int, method: PythonMethod, coveredLines: Set<Int>, allLines: Set<Int>) {
        val shiftedTime = time - timeShift;
        println("Shifted time --------------------- $shiftedTime")
        println("Real time ------------------------ $time")
        if (!timeSlots.containsKey(shiftedTime)) {
            timeSlots[shiftedTime] = TimeSlot(mutableMapOf())
        }
        val timeSlotData = timeSlots[shiftedTime]!!.collectedData
        if (!timeSlotData.containsKey(method)) {
            timeSlotData[method] = TestFunctionInfo(mutableSetOf(), allLines)
        }
        timeSlotData[method]?.coveredLines?.addAll(coveredLines)
    }

    fun clear() {
        timeSlots.clear()
    }

    fun getCoverage(time: Int): Float {
        val coverage = mutableMapOf<PythonMethod, TestFunctionInfo>()
        timeSlots.keys.filter { it <= time }.sorted().forEach {
            timeSlots[it]!!.collectedData.forEach { (method, info) ->
                if (!coverage.containsKey(method)) {
                    coverage[method] = TestFunctionInfo(mutableSetOf(), info.allLines)
                }
                coverage[method]?.coveredLines?.addAll(info.coveredLines)
            }
        }
        val coveredLines = coverage.values.map { it.coveredLines to it.allLines }
        if (coveredLines.isEmpty()) {
            return 0.toFloat()
        }

        val reduceCoverage = coveredLines.reduce { acc, pair ->
                (acc.first + pair.first).toMutableSet() to pair.second
            }

        if (reduceCoverage.second.isEmpty()) {
            return 0.toFloat()
        }
        return reduceCoverage.first.size.toFloat() / reduceCoverage.second.size
    }

    fun getCoveragePoints(maxTime: Int): List<Float> {
        val coveragePoints = (5..maxTime+10 step 5)
        return coveragePoints.map { getCoverage(it) }
    }
}

data class TimeSlot(
    val collectedData: MutableMap<PythonMethod, TestFunctionInfo>
)

data class TestFunctionInfo(
    val coveredLines: MutableSet<Int>,
    val allLines: Set<Int>,
)
