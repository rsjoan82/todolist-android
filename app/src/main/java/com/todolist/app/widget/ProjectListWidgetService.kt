package com.todolist.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.google.firebase.auth.FirebaseAuth
import com.todolist.app.R
import com.todolist.app.data.model.Project
import com.todolist.app.data.model.ProjectItem
import com.todolist.app.data.model.ProjectStatus
import com.todolist.app.data.repository.ProjectRepository
import java.util.Locale
import kotlinx.coroutines.runBlocking

class ProjectListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return ProjectListWidgetFactory(applicationContext, appWidgetId)
    }
}

private class ProjectListWidgetFactory(
    private val context: android.content.Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private sealed interface WidgetEntry {
        val id: Long

        data class Header(
            val title: String,
            override val id: Long
        ) : WidgetEntry

        data class ProjectRow(
            val projectId: String,
            val name: String,
            val status: String,
            val summary: String,
            override val id: Long
        ) : WidgetEntry
    }

    private data class WidgetProjectSummary(
        val project: Project,
        val pendingCount: Int,
        val totalCount: Int
    ) {
        val status: ProjectStatus
            get() = if (pendingCount > 0) ProjectStatus.PENDING else ProjectStatus.COMPLETED

        val shortSummary: String
            get() = "$pendingCount pendientes · $totalCount total"
    }

    private val repository = ProjectRepository()
    private var entries: List<WidgetEntry> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        entries = runBlocking {
            val user = FirebaseAuth.getInstance().currentUser ?: return@runBlocking emptyList()
            val projects = repository.getProjectsOnce(user.uid)
            val items = repository.getProjectItemsOnce(user.uid, projects.map { it.id })
            buildEntries(projects, items)
        }
    }

    override fun onDestroy() {
        entries = emptyList()
    }

    override fun getCount(): Int = entries.size

    override fun getViewAt(position: Int): RemoteViews {
        val entry = entries.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_projects_item)

        val views = RemoteViews(context.packageName, R.layout.widget_projects_item)
        when (entry) {
            is WidgetEntry.Header -> {
                views.setTextViewText(R.id.widgetProjectItemTitle, bold(entry.title))
                views.setTextColor(R.id.widgetProjectItemTitle, context.getColor(android.R.color.darker_gray))
                views.setViewVisibility(R.id.widgetProjectItemSubtitle, View.GONE)
            }

            is WidgetEntry.ProjectRow -> {
                views.setTextViewText(R.id.widgetProjectItemTitle, entry.name)
                views.setTextColor(R.id.widgetProjectItemTitle, context.getColor(android.R.color.black))
                views.setViewVisibility(R.id.widgetProjectItemSubtitle, View.VISIBLE)
                views.setTextViewText(
                    R.id.widgetProjectItemSubtitle,
                    "${entry.status} · ${entry.summary}"
                )
                views.setTextColor(
                    R.id.widgetProjectItemSubtitle,
                    if (entry.status == ProjectStatus.PENDING.label) {
                        context.getColor(android.R.color.holo_blue_dark)
                    } else {
                        context.getColor(android.R.color.darker_gray)
                    }
                )

                val openProjectIntent = Intent().apply {
                    putExtra(ProjectListWidgetProvider.EXTRA_PROJECT_ID, entry.projectId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                views.setOnClickFillInIntent(R.id.widgetProjectItemRoot, openProjectIntent)
                views.setOnClickFillInIntent(R.id.widgetProjectItemTitle, openProjectIntent)
                views.setOnClickFillInIntent(R.id.widgetProjectItemSubtitle, openProjectIntent)
            }
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = entries.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun buildEntries(projects: List<Project>, items: List<ProjectItem>): List<WidgetEntry> {
        if (projects.isEmpty()) return emptyList()

        val summaries = projects.map { project ->
            val projectItems = items.filter { it.projectId == project.id }
            WidgetProjectSummary(
                project = project,
                pendingCount = projectItems.count { !it.done },
                totalCount = projectItems.size
            )
        }

        val pending = summaries
            .filter { it.status == ProjectStatus.PENDING }
            .sortedWith(compareBy<WidgetProjectSummary> { it.project.name.lowercase(Locale.getDefault()) }.thenBy { it.project.id })

        val completed = summaries
            .filter { it.status == ProjectStatus.COMPLETED }
            .sortedWith(compareBy<WidgetProjectSummary> { it.project.name.lowercase(Locale.getDefault()) }.thenBy { it.project.id })

        val entries = mutableListOf<WidgetEntry>()

        if (pending.isNotEmpty()) {
            entries += WidgetEntry.Header("Pendientes", "pending_header".hashCode().toLong())
            pending.forEach { summary ->
                entries += WidgetEntry.ProjectRow(
                    projectId = summary.project.id,
                    name = summary.project.name,
                    status = summary.status.label,
                    summary = summary.shortSummary,
                    id = summary.project.id.hashCode().toLong()
                )
            }
        }

        if (completed.isNotEmpty()) {
            entries += WidgetEntry.Header("Completados", "completed_header".hashCode().toLong())
            completed.forEach { summary ->
                entries += WidgetEntry.ProjectRow(
                    projectId = summary.project.id,
                    name = summary.project.name,
                    status = summary.status.label,
                    summary = summary.shortSummary,
                    id = summary.project.id.hashCode().toLong()
                )
            }
        }

        return entries
    }

    private fun bold(text: String): CharSequence {
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
