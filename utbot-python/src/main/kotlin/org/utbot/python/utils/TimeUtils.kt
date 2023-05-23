package org.utbot.python.utils

fun now(): Int {
    return timeToSecond(System.currentTimeMillis())
}

fun timeToSecond(time: Long): Int {
    return (time / 1000).toInt()
}