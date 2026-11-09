package ru.ttraum.testcontainers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public enum class OutputType { STDOUT, STDERR }

/** One piece of container output, as Docker framed it (usually, but not necessarily, a line). */
public class OutputFrame(public val type: OutputType, public val bytes: ByteArray) {
    public val utf8String: String get() = bytes.decodeToString()
    override fun toString(): String = "$type: $utf8String"
}

/** A complete line of container output, without the line break. */
public data class LogLine(val type: OutputType, val text: String)

/** Receives container output while it is produced; see [GenericContainer.withLogConsumer]. */
public fun interface LogConsumer {
    public fun accept(frame: OutputFrame)
}

/** Collects everything it receives; handy for asserting on output. */
public class ToStringConsumer : LogConsumer {
    private val stdout = StringBuilder()
    private val stderr = StringBuilder()

    override fun accept(frame: OutputFrame) {
        when (frame.type) {
            OutputType.STDOUT -> stdout.append(frame.utf8String)
            OutputType.STDERR -> stderr.append(frame.utf8String)
        }
    }

    public fun stdout(): String = stdout.toString()
    public fun stderr(): String = stderr.toString()
    public fun toUtf8String(): String = stdout() + stderr()
    override fun toString(): String = toUtf8String()
}

/** Splits frames into lines per stream; a trailing partial line is emitted when the flow ends. */
public fun Flow<OutputFrame>.lines(): Flow<LogLine> = flow {
    val pending = mutableMapOf(OutputType.STDOUT to StringBuilder(), OutputType.STDERR to StringBuilder())
    collect { frame ->
        val buffer = pending.getValue(frame.type)
        buffer.append(frame.utf8String)
        while (true) {
            val idx = buffer.indexOf('\n')
            if (idx < 0) break
            emit(LogLine(frame.type, buffer.substring(0, idx).removeSuffix("\r")))
            buffer.deleteRange(0, idx + 1)
        }
    }
    pending.forEach { (type, buffer) -> if (buffer.isNotEmpty()) emit(LogLine(type, buffer.toString())) }
}

/** Incremental decoder of Docker's multiplexed stream: frames may be split across reads. */
internal class FrameDecoder {
    private var buffer = ByteArray(0)
    private var raw: Boolean? = null

    fun feed(bytes: ByteArray): List<OutputFrame> {
        if (raw == true) return listOf(OutputFrame(OutputType.STDOUT, bytes))
        buffer += bytes
        if (raw == null) {
            if (buffer.size < 4) return emptyList()
            // A TTY container sends raw output instead of 8-byte framed output.
            raw = !(buffer[0].toInt() in 0..2 && buffer[1].toInt() == 0 && buffer[2].toInt() == 0 && buffer[3].toInt() == 0)
            if (raw == true) return listOf(OutputFrame(OutputType.STDOUT, buffer)).also { buffer = ByteArray(0) }
        }
        val frames = mutableListOf<OutputFrame>()
        var pos = 0
        while (pos + 8 <= buffer.size) {
            val size = ((buffer[pos + 4].toInt() and 0xFF) shl 24) or ((buffer[pos + 5].toInt() and 0xFF) shl 16) or
                ((buffer[pos + 6].toInt() and 0xFF) shl 8) or (buffer[pos + 7].toInt() and 0xFF)
            if (pos + 8 + size > buffer.size) break
            val type = if (buffer[pos].toInt() == 2) OutputType.STDERR else OutputType.STDOUT
            frames += OutputFrame(type, buffer.copyOfRange(pos + 8, pos + 8 + size))
            pos += 8 + size
        }
        buffer = buffer.copyOfRange(pos, buffer.size)
        return frames
    }
}

