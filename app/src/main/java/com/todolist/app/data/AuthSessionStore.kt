package com.todolist.app.data

import android.content.Context

object AuthSessionStore {
    private const val PREFS_NAME = "auth_session"
    private const val KEY_LAST_UID = "last_uid"

    fun saveLastUid(context: Context, uid: String?) {
        val cleanUid = uid?.trim()?.takeIf { it.isNotEmpty() }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (cleanUid == null) {
                    remove(KEY_LAST_UID)
                } else {
                    putString(KEY_LAST_UID, cleanUid)
                }
            }
            .apply()
    }

    fun getLastUid(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_UID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
