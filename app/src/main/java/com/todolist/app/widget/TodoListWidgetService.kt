package com.todolist.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.google.firebase.auth.FirebaseAuth
import com.todolist.app.R
import com.todolist.app.data.model.Task
import com.todolist.app.data.repository.TaskRepository
import com.todolist.app.ui.TaskFilterPreferences
import kotlinx.coroutines.runBlocking

class TodoListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TodoListRemoteViewsFactory(applicationContext)
    }
}

private class TodoListRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private val repository = TaskRepository()
    private var tasks: List<Task> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            tasks = emptyList()
            return
        }

        val selectedTags = TaskFilterPreferences.getSelectedTags(context)
        tasks = runBlocking {
            runCatching {
                repository.getOpenTasksForWidget(uid, selectedTags, maxItems = 5)
            }.getOrElse {
                emptyList()
            }
        }
    }

    override fun onDestroy() {
        tasks = emptyList()
    }

    override fun getCount(): Int = tasks.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position !in tasks.indices) {
            return RemoteViews(context.packageName, R.layout.widget_todolist_item)
        }

        val task = tasks[position]
        return RemoteViews(context.packageName, R.layout.widget_todolist_item).apply {
            setTextViewText(R.id.widgetItemTitle, task.title)
            setImageViewResource(R.id.widgetItemAction, android.R.drawable.checkbox_off_background)

            val fillInIntent = Intent().apply {
                putExtra(TodoListWidgetProvider.EXTRA_TASK_ID, task.id)
            }
            setOnClickFillInIntent(R.id.widgetItemRoot, fillInIntent)
            setOnClickFillInIntent(R.id.widgetItemTitle, fillInIntent)
            setOnClickFillInIntent(R.id.widgetItemAction, fillInIntent)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = tasks.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
