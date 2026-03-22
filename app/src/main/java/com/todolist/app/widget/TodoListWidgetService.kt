package com.todolist.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.google.firebase.auth.FirebaseAuth
import com.todolist.app.R
import com.todolist.app.data.model.Tag
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskStatus
import com.todolist.app.data.repository.TagRepository
import com.todolist.app.data.repository.TaskRepository
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
            override val id: Long
        ) : WidgetEntry
    }

    private val repository = TaskRepository()
    private val tagRepository = TagRepository()
    private var entries: List<WidgetEntry> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        entries = runBlocking {
            val user = FirebaseAuth.getInstance().currentUser ?: return@runBlocking emptyList()
            val tasks = repository.getAllActiveTasksForWidget(user.uid)
            val tags = runCatching { tagRepository.getTagsOnce(user.uid) }.getOrDefault(emptyList())
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

                val openTaskFillInIntent = Intent().apply {
                    putExtra(TodoListWidgetProvider.EXTRA_TASK_ID, entry.taskId)
                    putExtra(TodoListWidgetProvider.EXTRA_ITEM_ACTION, TodoListWidgetProvider.ITEM_ACTION_OPEN_TASK)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.widgetItemRoot, openTaskFillInIntent)

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
            entries += WidgetEntry.TaskItem(
                taskId = task.id,
                title = task.title,
                isDone = false,
                id = task.id.hashCode().toLong()
            )
        }
        doneTasks.forEach { task ->
            entries += WidgetEntry.TaskItem(
                taskId = task.id,
                title = task.title,
                isDone = true,
                id = task.id.hashCode().toLong()
            )
        }
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
