package com.todolist.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.google.firebase.auth.FirebaseAuth
import com.todolist.app.MainActivity
import com.todolist.app.R
import com.todolist.app.data.model.TaskPatch
import com.todolist.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TodoListWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.todolist.app.widget.ACTION_REFRESH"
        const val ACTION_TOGGLE_DONE = "com.todolist.app.widget.ACTION_TOGGLE_DONE"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
        private const val REQUEST_REFRESH = 1001
        private const val REQUEST_ADD = 1002
        private const val REQUEST_TASK_CLICK = 1003

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun notifyWidgetDataChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TodoListWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetTaskList)
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_todolist)
            views.setTextViewText(R.id.widgetTitle, "TodoList")
            return views
        }
    }

    private val repository = TaskRepository()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelPeriodicRefresh(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        schedulePeriodicRefresh(context)
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                notifyWidgetDataChanged(context)
            }
            ACTION_TOGGLE_DONE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                if (taskId.isBlank()) return

                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
                widgetScope.launch {
                    runCatching {
                        repository.updateTask(uid, taskId, TaskPatch(toggleCompleted = true))
                    }
                    notifyWidgetDataChanged(context)
                }
            }
        }
    }

    private fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val views = buildRemoteViews(context)

        val serviceIntent = Intent(context, TodoListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widgetTaskList, serviceIntent)
        views.setEmptyView(R.id.widgetTaskList, R.id.widgetEmpty)

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

        val clickIntentTemplate = Intent(context, TodoListWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_DONE
        }
        val clickPendingIntentTemplate = PendingIntent.getBroadcast(
            context,
            REQUEST_TASK_CLICK + appWidgetId,
            clickIntentTemplate,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widgetTaskList, clickPendingIntentTemplate)

        manager.updateAppWidget(appWidgetId, views)
        manager.notifyAppWidgetViewDataChanged(intArrayOf(appWidgetId), R.id.widgetTaskList)
    }

    private fun schedulePeriodicRefresh(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TodoListWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + REFRESH_INTERVAL_MS,
            REFRESH_INTERVAL_MS,
            pendingIntent
        )
    }

    private fun cancelPeriodicRefresh(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TodoListWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
