package jp.trisplit;

// Told when tasks change (an app opened, moved to front, hidden...), so TriSplit reacts at once
// instead of at its next poll.
oneway interface ITaskEvents {
    void onTasksChanged();
}
