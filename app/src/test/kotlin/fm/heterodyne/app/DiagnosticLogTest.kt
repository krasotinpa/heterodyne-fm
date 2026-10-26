package fm.heterodyne.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun contents(dir: File) = dir.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        .joinToString("") { it.readText() }

    @Test fun readingAndExportRetainRedactionAndLiveListenerSeesRepeatedRx() {
        val log = DiagnosticLog(temporary.newFolder(), clock = { 123L })
        val live = DiagnosticFrames(2)
        log.frameListener = live::accept
        log.moduleSerial("private-serial")
        log.rx(byteArrayOf(1))
        log.rx(byteArrayOf(1))
        val output = java.io.ByteArrayOutputStream()
        log.copyTo(output)
        assertEquals(log.readTail(32768), output.toString("UTF-8"))
        assertFalse(log.readTail(32768).contains("private-serial"))
        assertTrue(log.readTail(32768).contains("×2"))
        assertEquals(2, live.snapshot().size)
    }

    @Test fun tailIsByteBoundedRedactedAndStartsAtWholeUtf8Record() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, clock = { 0L })
        assertEquals("", log.readTail(32768))
        repeat(1000) { log.event("Журнал 😀 запись-$it") }
        log.moduleSerial("private-serial")
        val tail = log.readTail(32768)
        assertTrue(tail.toByteArray(Charsets.UTF_8).size <= 32768)
        assertFalse(tail.contains("private-serial"))
        assertTrue(tail.contains("moduleSerialSha256="))
        assertFalse(tail.contains('�'))
        assertTrue(tail.lines().filter { it.isNotEmpty() }.all { it.startsWith("0 EVENT ") })
        log.event("Ж😀")
        assertEquals("0 EVENT Ж😀\n", log.readTail(15))
        assertEquals("", log.readTail(5))
        assertEquals("", log.readTail(0))
    }

    @Test fun recordsFramesEventsAndBuildVersionImmediately() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, clock = { 123L })
        log.tx(byteArrayOf(0xfa.toByte(), 0x55))
        log.rx(byteArrayOf(0x01))
        log.event("USB attached")
        log.buildVersion("0.1.0")
        val text = contents(dir)
        assertTrue(text.contains("123 TX fa55"))
        assertTrue(text.contains("123 RX 01 ×1 first=123 last=123"))
        assertTrue(text.contains("123 EVENT USB attached"))
        assertTrue(text.contains("123 EVENT build=0.1.0"))
    }

    @Test fun collapsesOnlyConsecutiveIdenticalRxAndCopiesInput() {
        val dir = temporary.newFolder()
        var time = 10L
        val log = DiagnosticLog(dir, clock = { time++ })
        val frame = byteArrayOf(1)
        log.rx(frame)
        frame[0] = 2
        log.rx(byteArrayOf(1))
        log.rx(byteArrayOf(1))
        assertEquals("10 RX 01 ×3 first=10 last=12\n", contents(dir))
        log.event("break")
        log.rx(byteArrayOf(1))
        log.tx(byteArrayOf(1))
        log.rx(byteArrayOf(1))
        log.rx(byteArrayOf(2))
        assertEquals(5, contents(dir).lines().count { it.contains(" RX ") || it.contains(" TX ") })
    }

    @Test fun evictsOldestWholeRecordsAndRejectsOversizedRecordWithinDiskLimit() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, maxBytes = 100, clock = { 0L })
        repeat(20) {
            log.event("entry-$it-Журнал")
            assertTrue(dir.listFiles()!!.sumOf { file -> file.length() } <= 100)
        }
        assertFalse(contents(dir).contains("entry-0-"))
        assertTrue(contents(dir).contains("entry-19-"))
        log.event("x".repeat(200))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 100)
        log.event("next")
        assertTrue(contents(dir).contains("next"))
    }

    @Test fun rotationLeavesRoomForManyShortRecordsAndRetainsWholeTail() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, maxBytes = 1000, clock = { 0L })
        val file = File(dir, "diagnostic.log")
        repeat(100) { log.event("x") } // Each record occupies 10 bytes.
        assertEquals(1000L, file.length())
        var rotations = 0
        repeat(20) {
            val before = java.nio.file.Files.readAttributes(
                file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            log.event("y")
            val after = java.nio.file.Files.readAttributes(
                file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            assertNotNull(before)
            assertNotNull(after)
            if (before != after) rotations++
            if (it == 0) assertEquals(900L, file.length())
            assertTrue(file.length() <= 1000)
            assertFalse(File(dir, "diagnostic.log.tmp").exists())
        }
        assertEquals(2, rotations)
        assertEquals("0 EVENT x\n".repeat(78) + "0 EVENT y\n".repeat(20), log.readTail(1000))
    }

    @Test fun hashesSerialBeforeWriting() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, clock = { 0L })
        log.moduleSerial("abc")
        assertTrue(contents(dir).contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertFalse(contents(dir).contains("abc"))
    }

    @Test fun acceptsSaltAndHashFunction() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, salt = byteArrayOf(7), hash = {
            assertArrayEquals(byteArrayOf(7, 97, 98, 99), it)
            ByteArray(32) { 0x12 }
        })
        log.moduleSerial("abc")
        assertTrue(contents(dir).contains("12".repeat(32)))
        assertFalse(contents(dir).contains("abc"))
    }

    @Test fun restartPreservesAndAppendsRecords() {
        val dir = temporary.newFolder()
        DiagnosticLog(dir, clock = { 1L }).event("before restart")
        val log = DiagnosticLog(dir, clock = { 2L })
        log.event("after restart")
        assertEquals("1 EVENT before restart\n2 EVENT after restart\n", contents(dir))
    }

    @Test fun restartContinuesConsecutiveRxSummary() {
        val dir = temporary.newFolder()
        DiagnosticLog(dir, clock = { 1L }).rx(byteArrayOf(1))
        DiagnosticLog(dir, clock = { 2L }).rx(byteArrayOf(1))
        assertEquals("1 RX 01 ×2 first=1 last=2\n", contents(dir))
    }

    @Test fun restartDiscardsInterruptedRotationAndPreservesOriginalRecords() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, clock = { 1L })
        log.event("before rotation")
        log.rx(byteArrayOf(1))
        val original = File(dir, "diagnostic.log").readBytes()
        val temporary = File(dir, "diagnostic.log.tmp")
        temporary.writeText("partial replacement")

        val restarted = DiagnosticLog(dir, clock = { 2L })

        assertFalse(temporary.exists())
        assertArrayEquals(original, File(dir, "diagnostic.log").readBytes())
        restarted.rx(byteArrayOf(1))
        assertEquals("1 EVENT before rotation\n1 RX 01 ×2 first=1 last=2\n", contents(dir))
    }

    @Test fun rxSummaryKeepsCorrectOffsetAfterRotation() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, maxBytes = 66, clock = { 1L })
        log.event("old".repeat(10))
        repeat(11) { log.rx(byteArrayOf(1)) }
        assertEquals("1 RX 01 ×11 first=1 last=1\n", contents(dir))
        assertFalse(File(dir, "diagnostic.log.tmp").exists())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 66)
    }

    @Test fun concurrentWritersPreserveAllEvents() {
        val dir = temporary.newFolder()
        val log = DiagnosticLog(dir, clock = { 0L })
        val threads = List(4) { worker -> Thread { repeat(50) { log.event("$worker:$it") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(200, contents(dir).lineSequence().filter { it.isNotEmpty() }.toSet().size)
    }

    @Test fun escapesLineBreaksInEvents() {
        val dir = temporary.newFolder()
        DiagnosticLog(dir, clock = { 0L }).event("one\ntwo\rthree")
        assertEquals("0 EVENT one\\ntwo\\rthree\n", contents(dir))
    }
}
