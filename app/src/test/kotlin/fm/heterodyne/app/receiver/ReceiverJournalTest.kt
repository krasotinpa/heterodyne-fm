package fm.heterodyne.app.receiver

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiverJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyRecordIdentifiesReceiverAndKeepsFrameBytes() {
        val log = DiagnosticLog(temporary.newFolder(), clock = { 1L })
        log.tx(byteArrayOf(0xfa.toByte(), 0x55))
        log.rx(byteArrayOf(1))
        log.rx(byteArrayOf(1))
        log.event("attached")
        log.moduleSerial("private")
        assertEquals("1 receiver=module TX fa55", log.readTail(32768).lineSequence().first())
        assertTrue(log.readTail(32768).contains("1 receiver=module RX 01 ×2 first=1 last=1\n"))
        assertTrue(log.readTail(32768).lineSequence().filter { it.isNotEmpty() }.all { it.contains(" receiver=module ") })
    }

    @Test fun restartNeverCollapsesFramesFromDifferentKinds() {
        val dir = temporary.newFolder()
        DiagnosticLog(dir, clock = { 1L }).rx(byteArrayOf(1))
        val log = DiagnosticLog(dir, clock = { 2L }, receiverKind = ReceiverKind("test"))
        log.rx(byteArrayOf(1))
        log.rx(byteArrayOf(1))
        log.event("attached")
        log.tx(byteArrayOf(0xfa.toByte(), 0x55))
        assertEquals(
            "1 receiver=module RX 01 ×1 first=1 last=1\n" +
                "2 receiver=test RX 01 ×2 first=2 last=2\n" +
                "2 receiver=test EVENT attached\n2 receiver=test TX fa55\n",
            log.readTail(32768),
        )
    }
}
