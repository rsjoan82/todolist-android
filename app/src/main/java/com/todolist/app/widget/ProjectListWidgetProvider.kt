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
import com.todolist.app.MainActivity
import com.todolist.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProjectListWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "PROJECT_WIDGET_REFRESH"
        const val ACTION_OPEN_PROJECT = "PROJECT_WIDGET_OPEN_PROJECT"
        const val EXTRA_PROJECT_ID = "extra_project_id"
        private const val LOG_TAG = "ProjectWidget"
        private const val REQUEST_REFRESH = 3001
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, ProjectListWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                    `package` = context.packageName
                }
            )
        }
    }

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

            ACTION_OPEN_PROJECT -> {
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )

                if (projectId.isBlank()) {
                    Log.w(LOG_TAG, "Ignoring widget open project without projectId. appWidgetId=$appWidgetId")
                    return
                }

                Log.d(LOG_TAG, "Widget open project requested. appWidgetId=$appWidgetId projectId=$projectId")

                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_OPEN_PROJECT_ID, projectId)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
        }
    }

    private suspend fun updateAppWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val user = FirebaseAuth.getInstance().currentUser
        val views = RemoteViews(context.packageName, R.layout.widget_projects)
        views.setTextViewText(R.id.widgetProjectsTitle, "Proyectos")
        views.setTextViewText(
            R.id.widgetProjectsEmpty,
            if (user == null) "Abre la app para iniciar sesion" else "No hay proyectos"
        )
        views.setEmptyView(R.id.widgetProjectsList, R.id.widgetProjectsEmpty)

        val serviceIntent = Intent(context, ProjectListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widgetProjectsList, serviceIntent)

        val refreshIntent = Intent(context, ProjectListWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH + appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetProjectsRefreshButton, refreshPendingIntent)

        val openIntentTemplate = Intent(context, ProjectListWidgetProvider::class.java).apply {
            action = ACTION_OPEN_PROJECT
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val openPendingIntentTemplate = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            openIntentTemplate,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widgetProjectsList, openPendingIntentTemplate)

        manager.updateAppWidget(appWidgetId, views)
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetProjectsList)
    }

    private suspend fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ProjectListWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetProjectsList)
            ids.forEach { appWidgetId ->
                updateAppWidget(context, manager, appWidgetId)
            }
        }
    }
}

