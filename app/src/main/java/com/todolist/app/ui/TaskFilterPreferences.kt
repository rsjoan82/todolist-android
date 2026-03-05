package com.todolist.app.ui

import android.content.Context

object TaskFilterPreferences {
    private const val PREFS_NAME = "task_filters"
    private const val KEY_SELECTED_TAGS = "selected_tags"

    fun saveSelectedTags(context: Context, tags: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED_TAGS, tags)
            .apply()
    }

    fun getSelectedTags(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED_TAGS, emptySet())
            ?.toSet()
            ?: emptySet()
    }
}
