package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TuneTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fake = FakeTransport()
    private val stations = mutableListOf<Pair<Band, Int?>>()
    private var now = 0L
    private val dir by lazy { temporary.newFolder() }
    private val controller by lazy {
        ModuleController(fake, DiagnosticLog(dir), stationChanged = { band, khz -> stations.add(band to khz) }, clock = { now }).also {
            it.found(DeviceProfile("module"))
            it.permissionResult(it.requestPermission()!!, true)
        }
    }
    // G4-8 RX SHA-256: 9139caa428c2875bab36d831ef6f558918330c16bac81186c2f4437964b11186.
    // Unchanged 71-byte frames at offsets 232, 4432, 6125 and 7851.
    private fun fixture(name: String) = javaClass.getResourceAsStream("/module/$name.bin")!!.use { it.readBytes() }
    private fun receive(bytes: ByteArray) { controller; fake.enqueueReceived(bytes); controller.receive() }
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun fmTuneUsesExactWhitelistFramesWithoutConfirmingTarget() {
        receive(fixture("fm28"))
        listOf(87500 to "FA 55 01 00 07 80 82 20 00 00 00 DE",
            97700 to "FA 55 02 00 07 80 82 20 66 00 66 12",
            108000 to "FA 55 03 00 07 80 82 20 CD 00 CD 44").forEach { (khz, expected) ->
            assertEquals(TuneResult.Sent, controller.tune(khz))
            assertArrayEquals(hex(expected), fake.sentFrames.last())
            assertEquals(listOf(Band.FM to 90300), stations)
            now += 1000
        }
    }
    @Test fun amIsDecodedButCannotBeSelectedSteppedOrSought() {
        receive(fixture("am31"))
        assertEquals(TuneTarget(Band.AM, 801), controller.snapshot.confirmed)
        for (frequency in listOf(711, 801, 1044, 90300)) {
            assertEquals(TuneResult.InvalidFrequency, controller.select(Band.AM, frequency))
        }
        assertEquals(TuneResult.BandNotFM, controller.step(Direction.Up))
        assertEquals(TuneResult.BandNotFM, controller.seek(Direction.Down))
        assertEquals(0, fake.sendCount)
    }

    @Test fun rejectsInvalidFrequenciesUnknownBandAndCommandsDuringPause() {
        assertEquals(TuneResult.BandNotFM, controller.tune(97700))
        receive(fixture("fm28"))
        listOf(Int.MIN_VALUE, 87400, 108100, 87501, Int.MAX_VALUE).forEach {
            assertEquals(TuneResult.InvalidFrequency, controller.tune(it))
        }
        assertEquals(0, fake.sendCount)
        assertEquals(TuneResult.Sent, controller.tune(97700))
        now = 999
        assertEquals(TuneResult.RateLimited, controller.tune(108000))
        assertEquals(1, fake.sendCount)
        now = 1000
        assertEquals(TuneResult.Sent, controller.tune(87500))
        now = 10000
        assertEquals(2, fake.sendCount)
        receive(fixture("am31"))
        assertEquals(Band.AM to 801, stations.last())
        assertEquals(TuneResult.Sent, controller.tune(97700))
        controller.close()
        assertEquals(TuneResult.NotConnected, controller.tune(97700))
    }

    @Test fun receivesEverySplitAndCoalescedFramesUsingOnlyLatestStation() {
        val fm = fixture("fm205")
        for (split in 1 until fm.size) {
            stations.clear()
            receive(fm.copyOfRange(0, split))
            assertTrue(stations.isEmpty())
            receive(fm.copyOfRange(split, fm.size))
            assertEquals(listOf(Band.FM to 108000), stations)
        }
        stations.clear()
        (fixture("fm0") + fixture("am31") + fixture("fm28")).forEach { receive(byteArrayOf(it)) }
        assertEquals(listOf(Band.FM to 87500, Band.AM to 801, Band.FM to 90300), stations)
        stations.clear()
        receive(fixture("fm205") + fixture("fm0"))
        assertEquals(listOf(Band.FM to 108000, Band.FM to 87500), stations)
    }

    private fun altered(frame: ByteArray, index: Int, value: Int): ByteArray = frame.copyOf().also {
        it[index] = value.toByte()
        it[it.lastIndex] = (-it.sliceArray(5 until it.lastIndex).sumOf { b -> b.toInt() and 255 }).toByte()
    }

    @Test fun malformedFramesGarbageAndFalseMarkersCannotConfirmStation() {
        val fm = fixture("fm28")
        receive(fm.copyOf().also { it[10] = 1 })
        receive(fm.copyOf().also { it[4] = 65 })
        receive(altered(fm, 7, 206))
        receive(altered(fm, 5, 0x23))
        assertTrue(stations.isEmpty())
        receive(ByteArray(20000) { 0xfa.toByte() } + hex("FA 55 01 FF FF") + hex("FA 55 01 0F FF") + fm)
        assertEquals(listOf(Band.FM to 90300), stations)
        receive(altered(fm, 6, 0x40))
        assertEquals(Band.DAB to null, stations.last())
        assertEquals(TuneResult.BandNotFM, controller.tune(97700))
    }

    @Test fun disconnectClearsPartialFrameAndConfirmedBand() {
        val fm = fixture("fm28")
        receive(fm.copyOfRange(0, 40))
        controller.close()
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        receive(fm.copyOfRange(40, fm.size))
        assertTrue(stations.isEmpty())
        assertEquals(TuneResult.BandNotFM, controller.tune(97700))
        receive(fm)
        assertEquals(TuneResult.Sent, controller.tune(97700))
    }

    @Test fun journalContainsTxAndEveryReceivedChunkEvenWithBadChecksum() {
        val fm = fixture("fm28")
        val bad = fm.copyOf().also { it[10] = 1 }
        receive(fm)
        receive(bad)
        controller.tune(87500)
        val text = java.io.File(dir, "diagnostic.log").readText()
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        assertTrue(text.contains("RX " + fm.hex()))
        assertTrue(text.contains("RX " + bad.hex()))
        assertTrue(text.contains("TX fa55010007808220000000de"))
        val file = java.io.File(dir, "diagnostic.log")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        now = 1000
        receive(fixture("fm205"))
        assertEquals(Band.FM to 108000, stations.last())
        assertEquals(TuneResult.Sent, controller.tune(97700))
    }

    @Test fun sequenceWrapsWithoutRetriesOrQueuedCommands() {
        receive(fixture("fm0"))
        repeat(257) { assertEquals(TuneResult.Sent, controller.tune(87500)); now += 1000 }
        assertEquals(255.toByte(), fake.sentFrames[254][2])
        assertEquals(0.toByte(), fake.sentFrames[255][2])
        assertEquals(1.toByte(), fake.sentFrames[256][2])
        assertEquals(257, fake.sendCount)
    }

    @Test fun writeFailureDisconnectsWithoutRetryOrFalseConfirmation() {
        receive(fixture("fm28"))
        fake.close()
        assertEquals(TuneResult.TransportError, controller.tune(97700))
        assertEquals(ConnectionState.Disconnected, controller.state)
        assertEquals(listOf(Band.FM to 90300), stations)
        now = 10000
        assertEquals(TuneResult.NotConnected, controller.tune(97700))
    }

    @Test fun markerInsideValidPayloadDoesNotCreateStation() {
        val body = byteArrayOf(0x21) + fixture("fm205") + byteArrayOf(0)
        val outer = byteArrayOf(0xfa.toByte(), 0x55, 1, 0, (body.size + 1).toByte()) +
            body + byteArrayOf((-body.sumOf { it.toInt() and 255 }).toByte())
        receive(outer)
        assertTrue(stations.isEmpty())
        receive(fixture("fm0"))
        assertEquals(listOf(Band.FM to 87500), stations)
    }

    @Test fun falseHeaderWithPlausibleLengthRecoversAfterBoundedCandidate() {
        val garbage = hex("FA 55 01 0F F0 23") + ByteArray(4079)
        receive(garbage + fixture("fm28"))
        assertEquals(listOf(Band.FM to 90300), stations)
    }

    @Test fun falseLongHeaderRecoversWithoutAdditionalBytes() {
        val fm = fixture("fm28")
        for (split in 0..fm.size) {
            stations.clear()
            receive(hex("FA 55 01 0F F0 23") + fm.copyOfRange(0, split))
            if (split < fm.size) receive(fm.copyOfRange(split, fm.size))
            assertEquals(listOf(Band.FM to 90300), stations)
            repeat(3) { receive(byteArrayOf()) }
            assertEquals(listOf(Band.FM to 90300), stations)
        }
    }

    @Test fun incompleteCandidateResyncsToEarliestCompleteValidMarker() {
        receive(hex("FA 55 01 0F F0 23") + fixture("fm0") + fixture("fm205"))
        assertEquals(listOf(Band.FM to 87500, Band.FM to 108000), stations)
    }

}
