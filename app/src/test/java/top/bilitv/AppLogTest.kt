package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.util.AppLog
import java.io.File
import java.util.concurrent.Executors

class AppLogTest {
    @Test fun concurrentLogsExportAndClearInOrder() {
        val file = File.createTempFile("bbtv-log-", ".txt")
        val field = AppLog::class.java.getDeclaredField("file").apply { isAccessible = true }
        field.set(AppLog, file)
        try {
            AppLog.clear()
            val workers = Executors.newFixedThreadPool(4)
            val jobs = (0 until 200).map { i -> workers.submit { AppLog.i("Test", "line=$i") } }
            jobs.forEach { it.get() }
            workers.shutdown()
            assertEquals(file.absolutePath, AppLog.exportPath())
            val lines = file.readLines()
            assertEquals(200, lines.size)
            assertEquals(200, lines.distinct().size)
            assertTrue(lines.all { Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} I/Test: line=\\d+").matches(it) })
            assertEquals(lines.joinToString("\n"), AppLog.snapshot())
            AppLog.clear()
            AppLog.i("Test", "after-clear")
            AppLog.exportPath()
            assertEquals(1, file.readLines().size)
            assertTrue(file.readText().contains("after-clear"))
        } finally {
            AppLog.exportPath()
            field.set(AppLog, null)
            AppLog.clear()
            file.delete()
        }
    }
}
