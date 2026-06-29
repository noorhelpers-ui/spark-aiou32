package com.example

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object ProfileStore {

    private const val PREFS_NAME = "SparkPrefs"
    
    // Core profile keys
    private const val KEY_STUDENT_ID = "student_id"
    private const val KEY_PASSWORD = "student_password"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_PROGRAM = "program"
    private const val KEY_SEMESTER = "semester"
    private const val KEY_COURSE_CODES = "course_codes"
    
    // Safety & session constraints keys
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    private const val KEY_PROFILE_LAST_EDITED = "profile_last_edited"
    private const val KEY_UPLOAD_COUNT = "upload_count_today"
    private const val KEY_UPLOAD_REFRESH_TIME = "upload_refresh_time"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if the student is logged in and if their 5-day session is still valid.
     */
    fun isSessionValid(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        if (!isLoggedIn) return false

        val lastLoginTime = prefs.getLong(KEY_LAST_LOGIN_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val fiveDaysInMs = 5 * 24 * 60 * 60 * 1000L

        // Auto-logout after 5 days
        if (currentTime - lastLoginTime > fiveDaysInMs) {
            prefs.edit().putBoolean(KEY_IS_LOGGED_IN, false).apply()
            return false
        }
        return true
    }

    /**
     * Establishes a new 5-day login session with complete backend profile details.
     */
    fun establishSession(
        context: Context,
        studentId: String,
        email: String,
        pass: String,
        fullName: String,
        level: String,
        program: String,
        semester: String,
        courseCodes: String
    ) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            .putString(KEY_STUDENT_ID, studentId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, pass)
            .putString(KEY_NAME, fullName)
            .putString("level", level)
            .putString(KEY_PROGRAM, program)
            .putString(KEY_SEMESTER, semester)
            .putString(KEY_COURSE_CODES, courseCodes)
            .apply()
    }

    /**
     * Terminate session manually.
     */
    fun logout(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
    }

    /**
     * Cache complete verified student session details locally to allow instant bypass of login requests on relogins.
     */
    fun saveVerifiedAccountSession(
        context: Context,
        email: String,
        pass: String,
        studentId: String,
        fullName: String,
        level: String,
        program: String,
        semester: String,
        courseCodes: String
    ) {
        val cleanEmail = email.trim().lowercase()
        getPrefs(context).edit()
            .putString("stored_email_$cleanEmail", cleanEmail)
            .putString("stored_password_$cleanEmail", pass.trim())
            .putString("stored_student_id_$cleanEmail", studentId)
            .putString("stored_name_$cleanEmail", fullName)
            .putString("stored_level_$cleanEmail", level)
            .putString("stored_program_$cleanEmail", program)
            .putString("stored_semester_$cleanEmail", semester)
            .putString("stored_course_codes_$cleanEmail", courseCodes)
            .apply()
    }

    /**
     * Retrieve complete cached verified session details.
     */
    fun getVerifiedAccountSession(context: Context, email: String): Map<String, String>? {
        val cleanEmail = email.trim().lowercase()
        val prefs = getPrefs(context)
        val savedEmail = prefs.getString("stored_email_$cleanEmail", "") ?: ""
        if (savedEmail.isEmpty()) return null
        
        return mapOf(
            "email" to savedEmail,
            "password" to (prefs.getString("stored_password_$cleanEmail", "") ?: ""),
            "student_id" to (prefs.getString("stored_student_id_$cleanEmail", "") ?: ""),
            "name" to (prefs.getString("stored_name_$cleanEmail", "") ?: ""),
            "level" to (prefs.getString("stored_level_$cleanEmail", "") ?: ""),
            "program" to (prefs.getString("stored_program_$cleanEmail", "") ?: ""),
            "semester" to (prefs.getString("stored_semester_$cleanEmail", "") ?: ""),
            "course_codes" to (prefs.getString("stored_course_codes_$cleanEmail", "") ?: "")
        )
    }

    /**
     * Persistent Administrative Block Check helper
     */
    fun isBlocked(context: Context): Boolean = getPrefs(context).getBoolean("is_blocked", false)
    fun setBlocked(context: Context, blocked: Boolean) {
        getPrefs(context).edit().putBoolean("is_blocked", blocked).apply()
    }

    /**
     * Persistent License Expiry Check helper
     */
    fun isExpired(context: Context): Boolean = getPrefs(context).getBoolean("is_expired", false)
    fun setExpired(context: Context, expired: Boolean) {
        getPrefs(context).edit().putBoolean("is_expired", expired).apply()
    }

    /**
     * Fully clear all preferences on manual device removal/data clearing.
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Rate limiting for background status checks to prevent redundant device counting on Sheets.
     */
    fun getLastStatusCheckTime(context: Context): Long = getPrefs(context).getLong("last_status_check_time", 0L)
    fun setLastStatusCheckTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong("last_status_check_time", time).apply()
    }

    /**
     * Get Student ID
     */
    fun getStudentId(context: Context): String = getPrefs(context).getString(KEY_STUDENT_ID, "") ?: ""
    fun getSavedPassword(context: Context): String = getPrefs(context).getString(KEY_PASSWORD, "") ?: ""

    // Profile settings info
    fun getName(context: Context): String = getPrefs(context).getString(KEY_NAME, "Registered Student") ?: ""
    fun getEmail(context: Context): String = getPrefs(context).getString(KEY_EMAIL, "student@spark.com") ?: ""
    fun getLevel(context: Context): String = getPrefs(context).getString("level", "BS") ?: ""
    fun getProgram(context: Context): String = getPrefs(context).getString(KEY_PROGRAM, "BS") ?: ""
    fun getSemester(context: Context): String = getPrefs(context).getString(KEY_SEMESTER, "1st") ?: ""
    fun getCourseCodes(context: Context): String = getPrefs(context).getString(KEY_COURSE_CODES, "1423, 8610") ?: ""

    /**
     * Modifies the user profile, enforcing the strict once-per-week rate limit.
     */
    fun updateProfile(
        context: Context,
        name: String,
        email: String,
        program: String,
        semester: String,
        courseCodes: String
    ): ProfileUpdateResult {
        val prefs = getPrefs(context)
        val lastEdited = prefs.getLong(KEY_PROFILE_LAST_EDITED, 0L)
        val currentTime = System.currentTimeMillis()
        val oneWeekInMs = 7 * 24 * 60 * 60 * 1000L

        // Enforce lock if they edited in the past 7 days (and they have already set profile once)
        if (lastEdited > 0L && (currentTime - lastEdited) < oneWeekInMs) {
            val nextAvailableTime = lastEdited + oneWeekInMs
            val calendar = Calendar.getInstance().apply { timeInMillis = nextAvailableTime }
            val formattedDate = String.format(
                "%d-%02d-%02d %02d:%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE)
            )
            return ProfileUpdateResult.CoolingDown(formattedDate)
        }

        prefs.edit()
            .putString(KEY_NAME, name)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PROGRAM, program)
            .putString(KEY_SEMESTER, semester)
            .putString(KEY_COURSE_CODES, courseCodes)
            .putLong(KEY_PROFILE_LAST_EDITED, currentTime)
            .apply()

        return ProfileUpdateResult.Success
    }

    /**
     * Increments internal tracked file upload contributions count. (Needs 2 uploads in past 12 hrs to unlock rewards)
     */
    fun getUploadedCount(context: Context): Int {
        val prefs = getPrefs(context)
        val lastReset = prefs.getLong(KEY_UPLOAD_REFRESH_TIME, 0L)
        val currentTime = System.currentTimeMillis()
        val twelveHoursInMs = 12 * 60 * 60 * 1000L

        if (currentTime - lastReset > twelveHoursInMs) {
            prefs.edit()
                .putInt(KEY_UPLOAD_COUNT, 0)
                .putLong(KEY_UPLOAD_REFRESH_TIME, currentTime)
                .apply()
            return 0
        }
        return prefs.getInt(KEY_UPLOAD_COUNT, 0)
    }

    fun incrementUploadedCount(context: Context) {
        val prefs = getPrefs(context)
        val nextCount = getUploadedCount(context) + 1
        prefs.edit()
            .putInt(KEY_UPLOAD_COUNT, nextCount)
            .apply()
    }
}

object OfflineStore {
    private const val PREFS_NAME = "SparkOfflinePrefs"
    private const val KEY_DOWNLOADED_FILES = "downloaded_files"

    data class OfflineFile(
        val title: String,
        val url: String,
        val localPath: String,
        val downloadTime: Long
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getDownloadedFiles(context: Context): List<OfflineFile> {
        val raw = getPrefs(context).getString(KEY_DOWNLOADED_FILES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").mapNotNull { item ->
            val parts = item.split("|||")
            if (parts.size >= 4) {
                OfflineFile(
                    title = parts[0],
                    url = parts[1],
                    localPath = parts[2],
                    downloadTime = parts[3].toLongOrNull() ?: 0L
                )
            } else null
        }
    }

    @Synchronized
    fun saveDownloadedFile(context: Context, title: String, url: String, localPath: String) {
        val files = getDownloadedFiles(context).toMutableList()
        // Prevent duplicate Urls
        files.removeAll { it.url == url }
        files.add(OfflineFile(title, url, localPath, System.currentTimeMillis()))
        
        val raw = files.joinToString(";;;") { "${it.title}|||${it.url}|||${it.localPath}|||${it.downloadTime}" }
        getPrefs(context).edit().putString(KEY_DOWNLOADED_FILES, raw).apply()
    }

    @Synchronized
    fun deleteDownloadedFile(context: Context, url: String) {
        val files = getDownloadedFiles(context).toMutableList()
        val match = files.find { it.url == url }
        if (match != null) {
            files.remove(match)
            try {
                val f = java.io.File(match.localPath)
                if (f.exists()) {
                    f.delete()
                }
            } catch (e: Exception) {
                // ignore
            }
            val raw = files.joinToString(";;;") { "${it.title}|||${it.url}|||${it.localPath}|||${it.downloadTime}" }
            getPrefs(context).edit().putString(KEY_DOWNLOADED_FILES, raw).apply()
        }
    }
}

sealed class ProfileUpdateResult {
    object Success : ProfileUpdateResult()
    data class CoolingDown(val availableDate: String) : ProfileUpdateResult()
}
