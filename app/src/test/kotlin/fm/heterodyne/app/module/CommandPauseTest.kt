package fm.heterodyne.app.module

import fm.heterodyne.app.DiagnosticLog
import fm.heterodyne.app.receiver.CommandPause
import fm.heterodyne.app.receiver.PauseKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandPauseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stepPauseSurvivesReplyAndRevealsRemainingSeekPause() {
        var now = 0L
        val fake = FakeTransport()
        val controller = ModuleController(fake, DiagnosticLog(temporary.newFolder()), clock = { now })
        controller.found(DeviceProfile("module"))
        controller.permissionResult(controller.requestPermission()!!, true)
        fun receive() {
            fake.enqueueReceived(javaClass.getResourceAsStream("/module/fm28.bin")!!.use { it.readBytes() })
            controller.receive()
        }
        receive()
        controller.seek(Direction.Down)
        now = 100; receive()
        now = 400; controller.tick()
        assertEquals(TuneResult.Sent, controller.step(Direction.Up))
        assertEquals(CommandPause(PauseKind.Step, Direction.Up, 500, 500, 400), controller.snapshot.pause)
        now = 500; receive()
        assertEquals(CommandPause(PauseKind.Step, Direction.Up, 500, 400, 500), controller.snapshot.pause)
        now = 899; controller.tick()
        assertEquals(PauseKind.Step, controller.snapshot.pause!!.kind)
        now = 900; controller.tick()
        assertEquals(CommandPause(PauseKind.Seek, Direction.Down, 5000, 4100, 900), controller.snapshot.pause)
    }

    @Test fun seekPauseIsPublishedWithoutRejectedPressAndRetainsDirection() {
        for ((fixture, duration) in listOf("fm28" to 5000L)) {
            for (direction in Direction.entries) {
                var now = 0L
                val snapshots = mutableListOf<TransitionSnapshot>()
                val fake = FakeTransport()
                val controller = ModuleController(fake, DiagnosticLog(temporary.newFolder()), clock = { now },
                    transitionObserved = { snapshots.add(it) })
                controller.found(DeviceProfile("module"))
                controller.permissionResult(controller.requestPermission()!!, true)
                fun receive() {
                    fake.enqueueReceived(javaClass.getResourceAsStream("/module/$fixture.bin")!!.use { it.readBytes() })
                    controller.receive()
                }
                receive()
                controller.seek(direction)
                assertEquals(CommandPause(PauseKind.Seek, direction, duration, duration), controller.snapshot.pause)
                now = 100; receive()
                now = 400; controller.tick()
                assertEquals(TransitionState.Idle, controller.transitionState)
                assertEquals(CommandPause(PauseKind.Seek, direction, duration, duration - 400, 400), snapshots.last().pause)
                val count = snapshots.size
                now = duration - 1; controller.tick()
                assertNotNull(controller.snapshot.pause)
                assertEquals(count, snapshots.size)
                assertEquals(TuneResult.RateLimited, controller.seek(if (direction == Direction.Up) Direction.Down else Direction.Up))
                assertEquals(direction, controller.snapshot.pause!!.direction)
                assertEquals(1, fake.sendCount)
                now = duration; controller.tick()
                assertNull(controller.snapshot.pause)
            }
        }
    }
}
