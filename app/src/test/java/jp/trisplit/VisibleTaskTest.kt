package jp.trisplit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleTaskTest {

    @Test
    fun `parses a task line`() {
        val task = VisibleTask.parse("912,6,0,89,984,2184,com.example/com.example.Main")!!
        assertEquals(912, task.taskId)
        assertEquals(6, task.windowingMode)
        assertEquals(Bounds(0, 89, 984, 2184), task.bounds)
        assertEquals("com.example", task.packageName)
    }

    @Test
    fun `rejects a malformed line`() {
        assertNull(VisibleTask.parse("912,6,0,89"))
        assertNull(VisibleTask.parse("x,6,0,89,984,2184,a/b"))
    }

    @Test
    fun `finds the other split-screen app`() {
        val own = VisibleTask(2, 6, Bounds(984, 89, 1968, 2184), "jp.trisplit/jp.trisplit.MainActivity")
        val partner = VisibleTask(1, 6, Bounds(0, 89, 984, 2184), "com.example/com.example.Main")
        val freeform = VisibleTask(3, 5, Bounds(0, 0, 900, 900), "com.other/com.other.Main")
        val home = VisibleTask(4, 1, Bounds(0, 0, 1968, 2184), "com.launcher/com.launcher.Home")
        assertEquals(partner, VisibleTask.findSplitPartner(listOf(own, freeform, partner, home), 2, setOf("jp.trisplit")))
    }

    @Test
    fun `no partner outside split screen`() {
        val own = VisibleTask(2, 1, Bounds(0, 0, 1968, 2184), "jp.trisplit/jp.trisplit.MainActivity")
        assertNull(VisibleTask.findSplitPartner(listOf(own), 2, setOf("jp.trisplit")))
    }

    @Test
    fun `partner keeps its side`() {
        val own = Bounds(984, 89, 1968, 2184)
        val left = VisibleTask(1, 6, Bounds(0, 89, 984, 2184), "a/b")
        val right = VisibleTask(1, 6, Bounds(1100, 89, 2184, 1968), "a/b")
        assertEquals(0, VisibleTask.partnerColumn(left, own, 3))
        assertEquals(2, VisibleTask.partnerColumn(right, Bounds(0, 79, 1092, 1968), 3))
    }

    @Test
    fun `finds the app TriSplit was dropped onto`() {
        val tasks = listOf(
            StageTask(20, parentTaskId = 7, visible = true, component = "jp.trisplit/jp.trisplit.MainActivity"),
            StageTask(11, parentTaskId = 6, visible = true, component = "a/a.Main"),
            StageTask(12, parentTaskId = 7, visible = false, component = "b/b.Main"),
        )
        assertEquals(12, StageTask.findReplaced(tasks, ownTaskId = 20, ignored = setOf("jp.trisplit"))?.taskId)
    }

    @Test
    fun `nothing replaced when TriSplit has its stage to itself`() {
        val tasks = listOf(
            StageTask(20, 7, true, "jp.trisplit/jp.trisplit.MainActivity"),
            StageTask(11, 6, true, "a/a.Main"),
        )
        assertNull(StageTask.findReplaced(tasks, 20, setOf("jp.trisplit")))
        assertEquals(StageTask(5, 3, false, "x/x.Y"), StageTask.parse("5,3,0,x/x.Y"))
    }

    @Test
    fun `top-bottom split maps top to the first column`() {
        val own = Bounds(0, 1100, 2184, 1968)
        val top = VisibleTask(1, 6, Bounds(0, 79, 2184, 1090), "a/b")
        assertEquals(0, VisibleTask.partnerColumn(top, own, 3))
    }

    @Test
    fun `home screen helper is neither partner nor replaced app`() {
        val ignored = setOf("jp.trisplit", "com.sec.android.app.launcher")
        val own = VisibleTask(2, 6, Bounds(984, 89, 1968, 2184), "jp.trisplit/jp.trisplit.MainActivity")
        val helper = VisibleTask(3, 6, Bounds(0, 89, 984, 2184), "com.sec.android.app.launcher/x.FromRecentActivity")
        assertNull(VisibleTask.findSplitPartner(listOf(own, helper), 2, ignored))
        val stage = listOf(
            StageTask(20, 7, true, "jp.trisplit/jp.trisplit.MainActivity"),
            StageTask(21, 7, false, "com.sec.android.app.launcher/x.FromRecentActivity"),
            StageTask(12, 7, false, "b/b.Main"),
        )
        assertEquals(12, StageTask.findReplaced(stage, 20, ignored)?.taskId)
    }

    @Test
    fun `third app in the top or bottom of one side is kept too`() {
        val ignored = setOf("jp.trisplit")
        val left = VisibleTask(1, 6, Bounds(0, 0, 1085, 1968), "a/a.Main")
        val topRight = VisibleTask(2, 6, Bounds(1099, 0, 2184, 1319), "b/b.Main")
        val own = VisibleTask(3, 6, Bounds(1099, 1333, 2184, 1968), "jp.trisplit/jp.trisplit.MainActivity")
        val tasks = listOf(own, topRight, left)
        val partner = VisibleTask.findSplitPartner(tasks, 3, ignored)!!
        assertEquals(1, partner.taskId)
        assertEquals(2, VisibleTask.findCompanion(tasks, 3, ignored, partner)?.taskId)
        assertNull(VisibleTask.findCompanion(listOf(own, left), 3, ignored, left))
    }
}
