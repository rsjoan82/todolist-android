package com.todolist.app.ui

import android.content.Context

object TaskFilterPreferences {
    private const val PREFS_NAME = "task_filters"
    private const val KEY_SELECTED_TAG_ID = "selected_tag_id"
    private const val KEY_LEGACY_SELECTED_TAG = "selected_tag"
    private const val KEY_LEGACY_SELECTED_TAGS = "selected_tags"

    fun saveSelectedTag(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_TAG_ID, tag)
            .remove(KEY_LEGACY_SELECTED_TAG)
            .remove(KEY_LEGACY_SELECTED_TAGS)
            .apply()
    }

    fun getSelectedTag(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val directTag = prefs.getString(KEY_SELECTED_TAG_ID, null)?.trim().orEmpty()
        if (directTag.isNotEmpty()) return directTag

        val legacyDirectTag = prefs.getString(KEY_LEGACY_SELECTED_TAG, null)?.trim().orEmpty()
        if (legacyDirectTag.isNotEmpty()) return legacyDirectTag

        return prefs
            .getStringSet(KEY_LEGACY_SELECTED_TAGS, emptySet())
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
