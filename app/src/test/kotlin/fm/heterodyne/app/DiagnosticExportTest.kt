package fm.heterodyne.app

import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportTest {
    @Test fun restoredResultDoesNotReportSuccessWithoutWriting() {
        val export = DiagnosticExport({}, {})
        var written = false
        assertNotNull(export.finish { written = true })
        assertFalse(written)
    }
    @Test fun disconnectsBeforeWriteAndRetriesAfterFailureOrCancellation() {
        val actions = mutableListOf<String>()
        val export = DiagnosticExport({ actions.add("close") }, { actions.add("retry") })
        export.begin()
        assertEquals(listOf("close"), actions)
        assertNotNull(export.finish { actions.add("write"); error("disk") })
        assertEquals(listOf("close", "write", "retry"), actions)
        export.begin()
        assertNull(export.finish())
        assertEquals("retry", actions.last())
    }
}
