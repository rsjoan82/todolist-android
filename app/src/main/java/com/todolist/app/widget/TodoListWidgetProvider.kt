package com.todolist.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.todolist.app.MainActivity
import com.todolist.app.R
import com.todolist.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TodoListWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "TODO_WIDGET_REFRESH"
        const val ACTION_TOGGLE_DONE = "TODO_WIDGET_DONE"
        const val ACTION_CLEAR_DONE = "TODO_WIDGET_CLEAR_DONE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_MARK_DONE = "extra_mark_done"
        const val EXTRA_APP_WIDGET_ID = "extra_app_widget_id"
        private const val LOG_TAG = "TodoListWidget"
        private const val REQUEST_REFRESH = 1001
        private const val REQUEST_ADD = 1002
        private const val REQUEST_CLEAR_DONE = 1003
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                val markDone = intent.getBooleanExtra(EXTRA_MARK_DONE, true)
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
                                    "status" to if (markDone) "done" else "open",
                                    "boardColumn" to if (markDone) "done" else "backlog",
                                    "completed" to markDone,
                                    "doneAt" to if (markDone) FieldValue.serverTimestamp() else null,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            )
                            .await()
                    }.onFailure { error ->
                        Log.e(LOG_TAG, "Error toggling task from widget. uid=$uid taskId=$taskId markDone=$markDone", error)
                    }
                    refreshAllWidgets(context)
                    pendingResult.finish()
                }
            }
            ACTION_CLEAR_DONE -> {
                val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                if (uid.isBlank()) return

                val pendingResult = goAsync()
                widgetScope.launch {
                    runCatching {
                        repository.archiveVisibleDoneTasksForWidget(uid)
                    }.onFailure { error ->
                        Log.e(LOG_TAG, "Error clearing done tasks from widget. uid=$uid", error)
                    }
                    refreshAllWidgets(context)
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val user = FirebaseAuth.getInstance().currentUser
        val views = RemoteViews(context.packageName, R.layout.widget_todolist)
        views.setTextViewText(R.id.widgetTitle, "TodoList")
        views.setTextViewText(
            R.id.widgetEmpty,
            if (user == null) "Abre la app para iniciar sesion" else "No hay tareas"
        )
        views.setEmptyView(R.id.widgetTaskList, R.id.widgetEmpty)

        val serviceIntent = Intent(context, TodoListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widgetTaskList, serviceIntent)

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
        views.setOnClickPendingIntent(R.id.widgetRefreshButton, refreshPendingIntent)

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
        views.setOnClickPendingIntent(R.id.widgetAddButton, addPendingIntent)

        val clearDoneIntent = Intent(context, TodoListWidgetProvider::class.java).apply {
            action = ACTION_CLEAR_DONE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val clearDonePendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CLEAR_DONE + appWidgetId,
            clearDoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetClearDoneButton, clearDonePendingIntent)

        val toggleIntentTemplate = Intent(context, TodoListWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_DONE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val togglePendingIntentTemplate = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            toggleIntentTemplate,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setPendingIntentTemplate(R.id.widgetTaskList, togglePendingIntentTemplate)

        manager.updateAppWidget(appWidgetId, views)
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetTaskList)
    }

    private suspend fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodoListWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetTaskList)
            ids.forEach { appWidgetId ->
                updateAppWidget(context, manager, appWidgetId)
            }
        }
    }
}
