package com.todolist.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.todolist.app.MainActivity
import com.todolist.app.R
import com.todolist.app.data.repository.TaskRepository
import com.todolist.app.ui.TaskFilterPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TodoListWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "TODO_WIDGET_REFRESH"
        const val ACTION_TOGGLE_DONE = "TODO_WIDGET_DONE"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val LOG_TAG = "TodoListWidget"
        private const val REQUEST_REFRESH = 1001
        private const val REQUEST_ADD = 1002
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private data class WidgetLine(
            val title: String,
            val taskId: String? = null
        )

        private fun buildRemoteViews(context: Context, lines: List<WidgetLine>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.todolist_widget)
            views.setTextViewText(R.id.title, "TodoList")
            views.setTextViewText(R.id.task1, lines.getOrElse(0) { WidgetLine("") }.title)
            views.setTextViewText(R.id.task2, lines.getOrElse(1) { WidgetLine("") }.title)
            views.setTextViewText(R.id.task3, lines.getOrElse(2) { WidgetLine("") }.title)
            views.setTextViewText(R.id.task4, lines.getOrElse(3) { WidgetLine("") }.title)
            views.setTextViewText(R.id.task5, lines.getOrElse(4) { WidgetLine("") }.title)
            return views
        }
    }

    private val repository = TaskRepository()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val pendingResult = goAsync()
        widgetScope.launch {
            appWidgetIds.forEach { appWidgetId ->
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
            pendingResult.finish()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val pendingResult = goAsync()
                widgetScope.launch {
                    refreshAllWidgets(context)
                    pendingResult.finish()
                }
            }
            ACTION_TOGGLE_DONE -> {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                if (uid.isBlank() || taskId.isBlank()) return

                val pendingResult = goAsync()
                widgetScope.launch {
                    runCatching {
                        FirebaseFirestore.getInstance()
                            .collection("users")
                            .document(uid)
                            .collection("tasks")
                            .document(taskId)
                            .update(
                                mapOf(
                                    "status" to "done",
                                    "completed" to true
                                )
                            )
                            .await()
                    }.onFailure { error ->
                        Log.e(LOG_TAG, "Error updating task as done from widget. uid=$uid taskId=$taskId", error)
                    }
                    refreshAllWidgets(context)
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val user = FirebaseAuth.getInstance().currentUser
        val hasUser = user != null
        val lines = when {
            user == null -> listOf(WidgetLine("Abre la app para iniciar sesion"))
            else -> {
                val selectedTags = TaskFilterPreferences.getSelectedTags(context)
                val fetchResult = runCatching {
                    repository.getOpenTasksForWidget(user.uid, selectedTags, maxItems = 5)
                }
                fetchResult.fold(
                    onSuccess = { tasks ->
                        if (tasks.isEmpty()) {
                            listOf(WidgetLine("No hay tareas abiertas"))
                        } else {
                            tasks.map { WidgetLine(title = it.title, taskId = it.id) }
                        }
                    },
                    onFailure = { error ->
                        Log.e(LOG_TAG, "Error loading widget tasks", error)
                        listOf(WidgetLine("Sin conexion"))
                    }
                )
            }
        }

        val normalizedLines = lines + List((5 - lines.size).coerceAtLeast(0)) { WidgetLine("") }
        val views = buildRemoteViews(context, normalizedLines)

        val refreshIntent = Intent(context, TodoListWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH + appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnRefresh, refreshPendingIntent)

        val addIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_CREATE_TASK, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val addPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_ADD + appWidgetId,
            addIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnAdd, addPendingIntent)

        val taskViewIds = listOf(R.id.task1, R.id.task2, R.id.task3, R.id.task4, R.id.task5)
        taskViewIds.forEachIndexed { index, viewId ->
            val taskId = normalizedLines.getOrNull(index)?.taskId
            val canClick = hasUser && !taskId.isNullOrBlank()
            val taskPendingIntent = if (canClick) {
                val taskIntent = Intent(context, TodoListWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE_DONE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(EXTRA_TASK_ID, taskId)
                }
                PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 10 + index,
                    taskIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null
            views.setBoolean(viewId, "setEnabled", canClick)
            views.setOnClickPendingIntent(viewId, taskPendingIntent)
        }

        manager.updateAppWidget(appWidgetId, views)
    }

    private suspend fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodoListWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            ids.forEach { appWidgetId ->
                updateAppWidget(context, manager, appWidgetId)
            }
        }
    }
}
