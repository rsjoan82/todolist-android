package com.todolist.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.google.firebase.auth.FirebaseAuth
import com.todolist.app.R
import com.todolist.app.data.AuthSessionStore
import com.todolist.app.data.model.Tag
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskStatus
import com.todolist.app.data.repository.TagRepository
import com.todolist.app.data.repository.TaskRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.runBlocking

class TodoListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return TodoListWidgetFactory(applicationContext, appWidgetId)
    }
}

private class TodoListWidgetFactory(
    private val context: android.content.Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {
    companion object {
        private const val LOG_TAG = "TodoListWidget"
    }

    private sealed interface WidgetEntry {
        val id: Long

        data class Header(
            val title: String,
            override val id: Long
        ) : WidgetEntry

        data class TaskItem(
            val taskId: String,
            val title: String,
            val isDone: Boolean,
            val dueDate: com.google.firebase.Timestamp?,
            val createdAt: com.google.firebase.Timestamp?,
            val completed: Boolean,
            val status: TaskStatus,
            val deadlineProgress: Int?,
            override val id: Long
        ) : WidgetEntry
    }

    private enum class DeadlineProgressColor {
        GREEN,
        YELLOW,
        ORANGE,
        RED
    }

    private val repository = TaskRepository()
    private val tagRepository = TagRepository()
    private var entries: List<WidgetEntry> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        entries = runBlocking {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
                ?: AuthSessionStore.getLastUid(context)
                ?: return@runBlocking emptyList()
            val tasks = repository.getAllActiveTasksForWidget(uid)
            val tags = runCatching { tagRepository.getTagsOnce(uid) }.getOrDefault(emptyList())
            Log.d(
                LOG_TAG,
                "onDataSetChanged appWidgetId=$appWidgetId uid=$uid tasks=${tasks.size} tags=${tags.size}"
            )
            buildEntries(tasks, tags)
        }
    }

    override fun onDestroy() {
        entries = emptyList()
    }

    override fun getCount(): Int = entries.size

    override fun getViewAt(position: Int): RemoteViews {
        val entry = entries.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_todolist_item)

        val views = RemoteViews(context.packageName, R.layout.widget_todolist_item)
        when (entry) {
            is WidgetEntry.Header -> {
                views.setViewVisibility(R.id.widgetItemAction, View.INVISIBLE)
                hideDeadlineProgress(views)
                views.setTextViewText(R.id.widgetItemTitle, bold(entry.title))
                views.setTextColor(R.id.widgetItemTitle, context.getColor(android.R.color.darker_gray))
            }
            is WidgetEntry.TaskItem -> {
                Log.d(
                    LOG_TAG,
                    "getViewAt taskId=${entry.taskId} title=${entry.title} dueDate=${entry.dueDate?.toDebugString() ?: "null"} createdAt=${entry.createdAt?.toDebugString() ?: "null"} completed=${entry.completed} status=${entry.status.value} calculatedProgress=${entry.deadlineProgress?.toString() ?: "null"} selectedProgressColor=${entry.deadlineProgress?.toDeadlineProgressColor()?.name ?: "none"}"
                )
                views.setViewVisibility(R.id.widgetItemAction, View.VISIBLE)
                views.setImageViewResource(
                    R.id.widgetItemAction,
                    if (entry.isDone) {
                        android.R.drawable.checkbox_on_background
                    } else {
                        android.R.drawable.checkbox_off_background
                    }
                )
                views.setTextViewText(
                    R.id.widgetItemTitle,
                    if (entry.isDone) struck(entry.title) else entry.title
                )
                views.setTextColor(R.id.widgetItemTitle, context.getColor(android.R.color.black))
                bindDeadlineProgress(views, entry.deadlineProgress)

                val openTaskFillInIntent = Intent().apply {
                    putExtra(TodoListWidgetProvider.EXTRA_TASK_ID, entry.taskId)
                    putExtra(TodoListWidgetProvider.EXTRA_ITEM_ACTION, TodoListWidgetProvider.ITEM_ACTION_OPEN_TASK)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.widgetItemRoot, openTaskFillInIntent)
                views.setOnClickFillInIntent(R.id.widgetItemTitle, openTaskFillInIntent)

                val toggleFillInIntent = Intent().apply {
                    putExtra(TodoListWidgetProvider.EXTRA_TASK_ID, entry.taskId)
                    putExtra(TodoListWidgetProvider.EXTRA_MARK_DONE, !entry.isDone)
                    putExtra(TodoListWidgetProvider.EXTRA_ITEM_ACTION, TodoListWidgetProvider.ITEM_ACTION_TOGGLE_DONE)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.widgetItemAction, toggleFillInIntent)
            }
        }
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = entries.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun buildEntries(tasks: List<Task>, tags: List<Tag>): List<WidgetEntry> {
        if (tasks.isEmpty()) return emptyList()

        val visibleTasks = tasks.filterNot { repository.shouldHideDoneTaskInWidget(it) }
        if (visibleTasks.isEmpty()) return emptyList()

        val tagNameById = tags.associate { it.id to it.name }
        val groupedTasks = visibleTasks.groupBy { task ->
            val tagId = task.tagId?.trim()?.takeIf { it.isNotEmpty() } ?: return@groupBy null
            tagNameById[tagId]?.trim()?.takeIf { it.isNotEmpty() }
        }

        val namedGroups = groupedTasks.keys
            .filterNotNull()
            .sortedBy { it.lowercase(Locale.getDefault()) }

        val entries = mutableListOf<WidgetEntry>()

        namedGroups.forEach { tag ->
            appendGroupEntries(entries, tag, groupedTasks[tag].orEmpty())
        }
        if (groupedTasks.containsKey(null)) {
            appendGroupEntries(entries, "Sin tag", groupedTasks[null].orEmpty())
        }

        return entries
    }

    private fun appendGroupEntries(
        entries: MutableList<WidgetEntry>,
        title: String,
        tasks: List<Task>
    ) {
        if (tasks.isEmpty()) return

        entries += WidgetEntry.Header(
            title = title,
            id = title.hashCode().toLong()
        )

        val openTasks = tasks
            .filter { it.status != TaskStatus.DONE && !it.completed }
            .sortedWith(
                compareByDescending<Task> { it.priority.rank }
                    .thenBy { it.dueDate?.seconds ?: Long.MAX_VALUE }
                    .thenByDescending { it.createdAt?.seconds ?: 0L }
            )

        val doneTasks = tasks
            .filter { it.status == TaskStatus.DONE || it.completed }
            .sortedBy { it.title.lowercase(Locale.getDefault()) }

        openTasks.forEach { task ->
            val progress = calculateDeadlineProgress(task, isDone = false)
            Log.d(
                LOG_TAG,
                "buildTaskEntry taskId=${task.id} title=${task.title} dueDate=${task.dueDate?.toDebugString() ?: "null"} createdAt=${task.createdAt?.toDebugString() ?: "null"} completed=${task.completed} status=${task.status.value} calculatedProgress=${progress?.toString() ?: "null"} selectedProgressColor=${progress?.toDeadlineProgressColor()?.name ?: "none"}"
            )
            entries += WidgetEntry.TaskItem(
                taskId = task.id,
                title = task.title,
                isDone = false,
                dueDate = task.dueDate,
                createdAt = task.createdAt,
                completed = task.completed,
                status = task.status,
                deadlineProgress = progress,
                id = task.id.hashCode().toLong()
            )
        }
        doneTasks.forEach { task ->
            val progress = calculateDeadlineProgress(task, isDone = true)
            Log.d(
                LOG_TAG,
                "buildTaskEntry taskId=${task.id} title=${task.title} dueDate=${task.dueDate?.toDebugString() ?: "null"} createdAt=${task.createdAt?.toDebugString() ?: "null"} completed=${task.completed} status=${task.status.value} calculatedProgress=${progress?.toString() ?: "null"} selectedProgressColor=${progress?.toDeadlineProgressColor()?.name ?: "none"}"
            )
            entries += WidgetEntry.TaskItem(
                taskId = task.id,
                title = task.title,
                isDone = true,
                dueDate = task.dueDate,
                createdAt = task.createdAt,
                completed = task.completed,
                status = task.status,
                deadlineProgress = progress,
                id = task.id.hashCode().toLong()
            )
        }
    }

    private fun bindDeadlineProgress(views: RemoteViews, progress: Int?) {
        hideDeadlineProgress(views)
        if (progress == null) return

        val progressViewId = when (progress.toDeadlineProgressColor()) {
            DeadlineProgressColor.GREEN -> R.id.widgetDeadlineProgressGreen
            DeadlineProgressColor.YELLOW -> R.id.widgetDeadlineProgressYellow
            DeadlineProgressColor.ORANGE -> R.id.widgetDeadlineProgressOrange
            DeadlineProgressColor.RED -> R.id.widgetDeadlineProgressRed
        }

        Log.d(
            LOG_TAG,
            "bindDeadlineProgress progress=$progress color=${progress.toDeadlineProgressColor().name} viewId=$progressViewId"
        )
        views.setViewVisibility(progressViewId, View.VISIBLE)
        views.setProgressBar(progressViewId, 100, progress, false)
    }

    private fun hideDeadlineProgress(views: RemoteViews) {
        listOf(
            R.id.widgetDeadlineProgressGreen,
            R.id.widgetDeadlineProgressYellow,
            R.id.widgetDeadlineProgressOrange,
            R.id.widgetDeadlineProgressRed
        ).forEach { progressViewId ->
            views.setViewVisibility(progressViewId, View.GONE)
            views.setProgressBar(progressViewId, 100, 0, false)
        }
    }

    private fun Int.toDeadlineProgressColor(): DeadlineProgressColor {
        return when {
            this >= 100 -> DeadlineProgressColor.RED
            this >= 80 -> DeadlineProgressColor.ORANGE
            this >= 50 -> DeadlineProgressColor.YELLOW
            else -> DeadlineProgressColor.GREEN
        }
    }

    private fun calculateDeadlineProgress(task: Task, isDone: Boolean): Int? {
        val dueDate = task.dueDate ?: return null
        val today = LocalDate.now()
        val dueLocalDate = dueDate.toLocalDate()

        if (isDone || task.completed || task.status == TaskStatus.DONE) return 100
        if (!today.isBefore(dueLocalDate)) return 100

        val createdLocalDate = task.createdAt?.toLocalDate() ?: return 0
        if (!createdLocalDate.isBefore(dueLocalDate)) return 0

        val totalDays = ChronoUnit.DAYS.between(createdLocalDate, dueLocalDate)
        if (totalDays <= 0L) return 0

        val elapsedDays = ChronoUnit.DAYS.between(createdLocalDate, today).coerceAtLeast(0L)
        return ((elapsedDays * 100L) / totalDays).toInt().coerceIn(0, 100)
    }

    private fun com.google.firebase.Timestamp.toLocalDate(): LocalDate {
        return Instant.ofEpochSecond(seconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    private fun com.google.firebase.Timestamp.toDebugString(): String {
        return "${toLocalDate()}(${seconds}s,$nanoseconds ns)"
    }

    private fun bold(text: String): CharSequence {
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun struck(text: String): CharSequence {
        return SpannableString(text).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
