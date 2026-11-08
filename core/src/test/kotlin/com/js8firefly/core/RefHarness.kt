package com.js8firefly.core

import java.io.BufferedReader
import java.io.File
import java.io.Writer

/**
 * Drives the C++ reference harness (tools/refharness in the iPhone repo) built
 * from the original JS8Call sources. Tests are skipped when it has not been built.
 * Protocol: tab-separated request lines on stdin, one reply line (or lines up
 * to "END") on stdout.
 */
class RefHarness : AutoCloseable {
    companion object {
        val path: String = System.getProperty("js8ref")
            ?: "${System.getProperty("user.home")}/Desktop/js8calliphone/tools/refharness/build/js8ref"
        val isAvailable: Boolean get() = File(path).canExecute()
    }

    private val process: Process = ProcessBuilder(path)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    private val writer: Writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val reader: BufferedReader = process.inputStream.bufferedReader(Charsets.UTF_8)

    fun readLine(): String = reader.readLine() ?: ""

    fun send(fields: List<String>) {
        writer.write(fields.joinToString("\t") + "\n")
        writer.flush()
    }

    fun call(fields: List<String>): String {
        send(fields)
        return readLine()
    }

    fun callUntilEnd(fields: List<String>): List<String> {
        send(fields)
        val lines = mutableListOf<String>()
        while (true) {
            val l = readLine()
            if (l == "END" || l.isEmpty()) break
            lines.add(l)
        }
        return lines
    }

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
    }
}
