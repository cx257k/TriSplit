package jp.trisplit;

import jp.trisplit.ITaskEvents;

import android.os.ParcelFileDescriptor;

// Implemented by SplitService, which Shizuku runs as the shell user (uid 2000).
interface ISplitService {
    // Reserved transaction code Shizuku calls when it tears the service down.
    void destroy() = 16777114;

    String exec(String command) = 1;

    // taskId[0] receives the id of the launched task, or -1 if it could not be found.
    String startFreeform(String component, int left, int top, int right, int bottom, int userId,
            boolean multipleTask, out int[] taskId) = 2;

    boolean resizeTask(int taskId, int left, int top, int right, int bottom) = 3;

    // TASK_STATE_SIZE ints per task, see SplitService.getTaskStates.
    int[] getTaskStates(in int[] taskIds) = 4;

    // One line per visible task, see SplitService.getVisibleTasks.
    String[] getVisibleTasks() = 5;

    // Takes a task out of split screen into a freeform window with the given bounds.
    String moveToFreeform(int taskId, int left, int top, int right, int bottom) = 6;

    // Whether the recent-apps overview is on screen.
    boolean isRecentsVisible() = 7;

    // Packages of recently used apps, most recent first.
    String[] getRecentPackages() = 8;

    // The home-screen wallpaper image, or null (e.g. a live wallpaper without a still image).
    ParcelFileDescriptor getWallpaper(int userId) = 9;

    // Puts two tasks into One UI's native split screen: mainTaskId in the main stage, sideTaskId in
    // the side stage at sidePosition (0 = top/left, 1 = bottom/right).
    String enterSplit(int mainTaskId, int sideTaskId, int sidePosition) = 10;

    // Brings the tasks to the front in this order (the last ends on top), restoring minimized ones.
    void moveTasksToFront(in int[] taskIds) = 11;

    // Remembers that these apps were tiled at these bounds (4 ints per app), and the ordinary
    // pop-up bounds to give them if they later open as a pop-up at exactly those tiled bounds.
    void markTiled(in String[] packages, in int[] tiledBounds, in int[] popupBounds) = 12;

    // Tasks TriSplit is managing right now; the pop-up fixer leaves them alone.
    void setManagedTasks(in int[] taskIds) = 13;

    // Closes these tasks, like their windows' close buttons.
    void removeTasks(in int[] taskIds) = 14;

    // Every split-screen task, shown or not, most recent first: "taskId,parentTaskId,visible,component".
    String[] getStageTasks() = 15;

    // Whether the on-screen keyboard is showing.
    boolean isImeVisible() = 17;

    // Receives task changes as they happen; null stops them.
    void setTaskEvents(ITaskEvents listener) = 18;
}
