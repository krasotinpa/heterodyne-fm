package fm.heterodyne.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DragTargetIndexTest {
    @Test fun insertionChangesWhenDraggedCenterCrossesHalfAStride() {
        assertEquals(0, dragTargetIndex(0, 31f, 64f, 3))
        assertEquals(1, dragTargetIndex(0, 33f, 64f, 3))
        assertEquals(2, dragTargetIndex(2, -31f, 64f, 3))
        assertEquals(1, dragTargetIndex(2, -33f, 64f, 3))
    }

}
