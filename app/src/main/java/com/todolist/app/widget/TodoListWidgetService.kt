package com.todolist.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        private const val TAG = "TodoListWidget"
        private const val PROGRESS_BITMAP_WIDTH = 420
        private const val PROGRESS_BITMAP_HEIGHT = 32
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
            logTaskProgress(task, isDone = false, progress = progress)
            entries += WidgetEntry.TaskItem(
                taskId = task.id,
                title = task.title,
                isDone = false,
                deadlineProgress = progress,
                id = task.id.hashCode().toLong()
            )
        }
        doneTasks.forEach { task ->
            val progress = calculateDeadlineProgress(task, isDone = true)
            logTaskProgress(task, isDone = true, progress = progress)
            entries += WidgetEntry.TaskItem(
                taskId = task.id,
                title = task.title,
                isDone = true,
                deadlineProgress = progress,
                id = task.id.hashCode().toLong()
            )
        }
    }

    private fun bindDeadlineProgress(views: RemoteViews, progress: Int?) {
        hideDeadlineProgress(views)
        if (progress == null) {
            Log.d(TAG, "bindDeadlineProgress hidden: no dueDate")
            return
        }

        Log.d(
            TAG,
            "bindDeadlineProgress visible progress=$progress color=${progress.toDeadlineProgressColor()}"
        )
        views.setViewVisibility(R.id.widgetDeadlineProgressBackground, View.VISIBLE)
        views.setImageViewBitmap(
            R.id.widgetDeadlineProgressBackground,
            createDeadlineProgressBitmap(progress)
        )
    }

    private fun hideDeadlineProgress(views: RemoteViews) {
        views.setViewVisibility(R.id.widgetDeadlineProgressBackground, View.GONE)
    }

    private fun Int.toDeadlineProgressColor(): DeadlineProgressColor {
        return when {
            this >= 100 -> DeadlineProgressColor.RED
            this >= 80 -> DeadlineProgressColor.ORANGE
            this >= 50 -> DeadlineProgressColor.YELLOW
            else -> DeadlineProgressColor.GREEN
        }
    }

    private fun DeadlineProgressColor.trackColor(): Int {
        return when (this) {
            DeadlineProgressColor.GREEN -> Color.argb(115, 205, 239, 214)
            DeadlineProgressColor.YELLOW -> Color.argb(125, 255, 243, 191)
            DeadlineProgressColor.ORANGE -> Color.argb(130, 255, 216, 168)
            DeadlineProgressColor.RED -> Color.argb(135, 248, 199, 199)
        }
    }

    private fun DeadlineProgressColor.fillColor(): Int {
        return when (this) {
            DeadlineProgressColor.GREEN -> Color.argb(175, 124, 210, 145)
            DeadlineProgressColor.YELLOW -> Color.argb(185, 245, 204, 92)
            DeadlineProgressColor.ORANGE -> Color.argb(190, 238, 143, 65)
            DeadlineProgressColor.RED -> Color.argb(195, 225, 98, 98)
        }
    }

    private fun createDeadlineProgressBitmap(progress: Int): Bitmap {
        val safeProgress = progress.coerceIn(0, 100)
        val color = safeProgress.toDeadlineProgressColor()
        val bitmap = Bitmap.createBitmap(
            PROGRESS_BITMAP_WIDTH,
            PROGRESS_BITMAP_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val paint = Paint()

        paint.color = color.trackColor()
        canvas.drawRect(
            0f,
            0f,
            PROGRESS_BITMAP_WIDTH.toFloat(),
            PROGRESS_BITMAP_HEIGHT.toFloat(),
            paint
        )

        if (safeProgress > 0) {
            paint.color = color.fillColor()
            val fillWidth = (PROGRESS_BITMAP_WIDTH * safeProgress / 100f).coerceAtLeast(2f)
            canvas.drawRect(0f, 0f, fillWidth, PROGRESS_BITMAP_HEIGHT.toFloat(), paint)
        }

        return bitmap
    }

    private fun logTaskProgress(task: Task, isDone: Boolean, progress: Int?) {
        val color = progress?.toDeadlineProgressColor()
        Log.d(
            TAG,
            "renderTask taskId=${task.id} title=${task.title} " +
                "dueDate=${task.dueDate} createdAt=${task.createdAt} " +
                "completed=${task.completed} status=${task.status} isDone=$isDone " +
                "calculatedProgress=$progress selectedProgressColor=$color"
        )
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
