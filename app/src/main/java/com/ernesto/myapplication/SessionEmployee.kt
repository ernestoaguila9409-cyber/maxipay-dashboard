package com.ernesto.myapplication

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the current logged-in employee name so it can be used when voiding/refunding
 * even if the activity wasn't started with employeeName in the intent.
 */
object SessionEmployee {

    private const val PREFS_NAME = "pos_session"
    private const val KEY_EMPLOYEE_NAME = "employeeName"
    private const val KEY_EMPLOYEE_ROLE = "employeeRole"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setEmployee(context: Context, name: String, role: String) {
        prefs(context).edit()
            .putString(KEY_EMPLOYEE_NAME, name)
            .putString(KEY_EMPLOYEE_ROLE, role)
            .apply()
    }

    fun setEmployeeName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_EMPLOYEE_NAME, name).apply()
    }

    fun getEmployeeName(context: Context): String =
        prefs(context).getString(KEY_EMPLOYEE_NAME, "") ?: ""

    fun getEmployeeRole(context: Context): String =
        prefs(context).getString(KEY_EMPLOYEE_ROLE, "") ?: ""
}
