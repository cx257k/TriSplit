package jp.trisplit

/**
 * A split-screen task as reported by [SplitService.getStageTasks]; [parentTaskId] is its stage.
 */
data class StageTask(val taskId: Int, val parentTaskId: Int, val visible: Boolean, val component: String) {
    val packageName: String get() = component.substringBefore('/')

    companion object {
        fun parse(line: String): StageTask? {
            val parts = line.split(',', limit = 4)
            if (parts.size != 4) return null
            val id = parts[0].toIntOrNull() ?: return null
            val parent = parts[1].toIntOrNull() ?: return null
            return StageTask(id, parent, parts[2] == "1", parts[3])
        }

        /**
         * The app TriSplit was dropped onto: the most recent other task in TriSplit's own stage,
         * now hidden underneath it. Null when TriSplit opened a split by itself. Tasks of [ignored]
         * packages (TriSplit, the home screen's helper screens) are not apps the user had open.
         */
        fun findReplaced(tasks: List<StageTask>, ownTaskId: Int, ignored: Set<String>): StageTask? {
            val stage = tasks.firstOrNull { it.taskId == ownTaskId }?.parentTaskId ?: return null
            if (stage < 0) return null
            return tasks.firstOrNull { it.parentTaskId == stage && it.taskId != ownTaskId && it.packageName !in ignored }
        }
    }
}

/**
 * A task visible on screen, as reported by [SplitService.getVisibleTasks].
 * [component] is the flattened base activity, or empty when unknown.
 */
data class VisibleTask(val taskId: Int, val windowingMode: Int, val bounds: Bounds, val component: String) {
    val packageName: String get() = component.substringBefore('/')

    companion object {
        /** WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW (hidden): a side of split screen. */
        const val WINDOWING_MODE_MULTI_WINDOW = 6

        fun parse(line: String): VisibleTask? {
            val parts = line.split(',', limit = 7)
            if (parts.size != 7) return null
            val n = parts.take(6).map { it.toIntOrNull() ?: return null }
            return VisibleTask(n[0], n[1], Bounds(n[2], n[3], n[4], n[5]), parts[6])
        }

        /**
         * The app sharing split screen with TriSplit: another visible split-screen task that is
         * not TriSplit's own nor of an [ignored] package. If several qualify, the largest.
         */
        fun findSplitPartner(tasks: List<VisibleTask>, ownTaskId: Int, ignored: Set<String>): VisibleTask? =
            tasks
                .filter { it.taskId != ownTaskId && it.windowingMode == WINDOWING_MODE_MULTI_WINDOW }
                .filter { it.packageName !in ignored }
                .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

        /**
         * A third app already in the split besides [partner]: One UI can split one side into top
         * and bottom, so TriSplit may share the screen with two apps. The larger if several.
         */
        fun findCompanion(tasks: List<VisibleTask>, ownTaskId: Int, ignored: Set<String>, partner: VisibleTask): VisibleTask? =
            tasks
                .filter { it.taskId != ownTaskId && it.taskId != partner.taskId && it.windowingMode == WINDOWING_MODE_MULTI_WINDOW }
                .filter { it.packageName !in ignored && it.packageName != partner.packageName }
                .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

        /**
         * Column the partner should take so it stays on its side: the first column if it sits left
         * of (or above) TriSplit, otherwise the last.
         */
        fun partnerColumn(partner: VisibleTask, own: Bounds?, columnCount: Int): Int {
            if (own == null) return 0
            val dx = (partner.bounds.left + partner.bounds.right) - (own.left + own.right)
            val dy = (partner.bounds.top + partner.bounds.bottom) - (own.top + own.bottom)
            val before = if (dx != 0) dx < 0 else dy < 0
            return if (before) 0 else columnCount - 1
        }
    }
}
