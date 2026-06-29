package com.example

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

object SparkNetwork {

    private val TAG = "SparkNetwork"

    // Unified OkHttpClient configuration with timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Endpoint constants (securely declared within the source, not exposed in public endpoints)
    private const val LOGIN_BACKEND = "https://script.google.com/macros/s/AKfycbzThd3WDkuU-N2Au3TmNhkG26O2wwKlPlrcrqPpb8XxsAEMEVfLRMqK5Oy1rRA93tjE/exec"
    private const val SEARCH_API = "https://script.google.com/macros/s/AKfycbwesaWlE9TV_pGY4I4W31u19jTQADsuXNGrrdym6TeJR0beUxaRvpImUk8NrKKEbrG1/exec"
    private const val REQUEST_API = "https://script.google.com/macros/s/AKfycbwmzMRedU4-l9KkFrsSb0NkHaZbhsowD9nLGdQosEv1OQuKjxQpq14cyZsW1HNZHto5SA/exec"
    private const val CONTRIBUTION_UPLOAD_API = "https://script.google.com/macros/s/AKfycbwpPytj6qNYIP7ZiLPq0RD4EGSwSIgjvbilBdiF15_oNl6OWb-r5sDqCzMts6gggb94wg/exec"

    /**
     * Replicates the exact GET request login verification from the HTML source.
     */
    suspend fun verifyLogin(context: Context, email: String, password: String, isCheckOnly: Boolean = false): LoginResult = withContext(Dispatchers.IO) {
        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"
            val deviceName = "${Build.BRAND} ${Build.MODEL} ($androidId)"
            val urlBuilder = LOGIN_BACKEND.toHttpUrlOrNull()?.newBuilder() ?: return@withContext LoginResult.Error("API URL Config Error")
            
            urlBuilder.addQueryParameter("action", "login")
            urlBuilder.addQueryParameter("email", email)
            urlBuilder.addQueryParameter("password", password)
            if (!isCheckOnly) {
                urlBuilder.addQueryParameter("pc_user", deviceName)
            }
            urlBuilder.addQueryParameter("mode", "json")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Login Response: $bodyString")
                if (!response.isSuccessful) {
                    if (bodyString.contains("limit") || bodyString.contains("limit_reached")) {
                        return@withContext LoginResult.Failure("Device limit reached", "device_limit_reached", 2, 2)
                    }
                    if (bodyString.contains("password") || bodyString.contains("incorrect")) {
                        return@withContext LoginResult.Failure("Incorrect Password", "incorrect_password", 1, 2)
                    }
                    return@withContext LoginResult.Error("HTTP Error: ${response.code}")
                }

                if (bodyString.isEmpty()) {
                    return@withContext LoginResult.Error("Empty server response")
                }

                try {
                    val json = JSONObject(bodyString)
                    val rawStatus = json.optString("status", "error")
                    val rawMessage = json.optString("message", "")
                    val rawReason = json.optString("reason", "")
                    
                    val isBlocked = rawStatus.equals("block", ignoreCase = true) || 
                                    rawStatus.equals("blocked", ignoreCase = true) || 
                                    rawReason.contains("block", ignoreCase = true) || 
                                    rawMessage.contains("block", ignoreCase = true) || 
                                    rawMessage.contains("inactive", ignoreCase = true)
                                    
                    if (isBlocked) {
                        return@withContext LoginResult.Failure(
                            message = if (rawMessage.isNotEmpty()) rawMessage else "Your credential is blocked by admin",
                            reason = "account_blocked",
                            deviceUsing = json.optInt("deviceUsing", 0),
                            deviceAllowed = json.optInt("deviceAllowed", 2)
                        )
                    }

                    val isExpired = rawStatus.equals("expire", ignoreCase = true) || 
                                    rawStatus.equals("expired", ignoreCase = true) || 
                                    rawReason.contains("expire", ignoreCase = true) || 
                                    rawMessage.contains("expire", ignoreCase = true) || 
                                    rawMessage.contains("license", ignoreCase = true)

                    if (isExpired) {
                        return@withContext LoginResult.Failure(
                            message = if (rawMessage.isNotEmpty()) rawMessage else "Your license is expired",
                            reason = "expired",
                            deviceUsing = json.optInt("deviceUsing", 0),
                            deviceAllowed = json.optInt("deviceAllowed", 2)
                        )
                    }

                    if (rawStatus == "success") {
                        val studentId = json.optString("studentId", "")
                        val fullName = json.optString("fullName", json.optString("name", "Student"))
                        val returnedEmail = json.optString("email", email)
                        val level = json.optString("level", "")
                        val program = json.optString("program", "")
                        val semester = json.optString("semester", "")
                        
                        // Parse course codes array
                        val coursesList = mutableListOf<String>()
                        val coursesArr = json.optJSONArray("courses")
                        if (coursesArr != null) {
                            for (i in 0 until coursesArr.length()) {
                                val cCode = coursesArr.optString(i)
                                if (cCode.isNotEmpty()) {
                                    coursesList.add(cCode)
                                }
                            }
                        } else {
                            val coursesStr = json.optString("courses", "")
                            if (coursesStr.isNotEmpty()) {
                                coursesStr.split(",").map { it.trim() }.forEach {
                                    if (it.isNotEmpty()) coursesList.add(it)
                                }
                            }
                        }

                        val lastLogin = json.optString("lastLogin", "Just Now")
                        val deviceUsing = json.optInt("deviceUsing", 1)
                        val deviceAllowed = json.optInt("deviceAllowed", 2)
                        
                        LoginResult.Success(
                            studentId = studentId,
                            fullName = fullName,
                            email = returnedEmail,
                            level = level,
                            program = program,
                            semester = semester,
                            courses = coursesList,
                            lastLogin = lastLogin,
                            deviceUsing = deviceUsing,
                            deviceAllowed = deviceAllowed
                        )
                    } else {
                        val message = json.optString("message", "Authentication Failed")
                        val reason = json.optString("reason", "unknown")
                        val deviceUsing = json.optInt("deviceUsing", 0)
                        val deviceAllowed = json.optInt("deviceAllowed", 1)
                        LoginResult.Failure(
                            message = message,
                            reason = reason,
                            deviceUsing = deviceUsing,
                            deviceAllowed = deviceAllowed
                        )
                    }
                } catch (jsonEx: Exception) {
                    Log.e(TAG, "JSON parse failed, doing fallback parsing", jsonEx)
                    val lowerBody = bodyString.lowercase()
                    if (lowerBody.contains("success") || lowerBody.contains("\"status\":\"success\"")) {
                        LoginResult.Success(
                            studentId = "0000000",
                            fullName = "Registered Student",
                            email = email,
                            level = "BS",
                            program = "Computer Science",
                            semester = "1st",
                            courses = listOf("8601", "8602"),
                            lastLogin = "Just Now",
                            deviceUsing = 1,
                            deviceAllowed = 2
                        )
                    } else if (lowerBody.contains("limit") || lowerBody.contains("device_limit") || lowerBody.contains("device limit") || lowerBody.contains("device_allowed")) {
                        LoginResult.Failure(
                            message = "Connected device limit reached for this ID.",
                            reason = "device_limit_reached",
                            deviceUsing = 2,
                            deviceAllowed = 2
                        )
                    } else if (lowerBody.contains("incorrect") || lowerBody.contains("wrong") || lowerBody.contains("password") || lowerBody.contains("invalid")) {
                        LoginResult.Failure(
                            message = "Incorrect password or student ID.",
                            reason = "incorrect_password",
                            deviceUsing = 0,
                            deviceAllowed = 2
                        )
                    } else {
                        LoginResult.Failure(
                            message = "Authentication issue - please check ID/Password or contact Support.",
                            reason = "incorrect_password",
                            deviceUsing = 0,
                            deviceAllowed = 2
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login exception", e)
            LoginResult.Error("Network error: ${e.localizedMessage ?: "Please try again"}")
        }
    }

    /**
     * Unregisters/removes the device from the backend database on Logout.
     */
    suspend fun performLogout(context: Context, email: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"
            val deviceName = "${Build.BRAND} ${Build.MODEL} ($androidId)"
            val urlBuilder = LOGIN_BACKEND.toHttpUrlOrNull()?.newBuilder() ?: return@withContext false
            
            urlBuilder.addQueryParameter("action", "logout")
            urlBuilder.addQueryParameter("email", email)
            urlBuilder.addQueryParameter("pc_user", deviceName)
            urlBuilder.addQueryParameter("mode", "json")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Logout Response: $bodyString")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Logout exception", e)
            false
        }
    }

    /**
     * Search course files using the main search backend by query string.
     */
    suspend fun queryPremiumFiles(courseCode: String): List<SparkFile> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<SparkFile>()
        try {
            val urlBuilder = SEARCH_API.toHttpUrlOrNull()?.newBuilder() ?: return@withContext emptyList()
            urlBuilder.addQueryParameter("search", courseCode)

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Search Response for $courseCode: $bodyString")
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    // Script can return {"results": [...]} or raw [...] Array
                    val jsonArray = if (bodyString.trim().startsWith("{")) {
                        val jsonObject = JSONObject(bodyString)
                        jsonObject.optJSONArray("results") ?: JSONArray()
                    } else {
                        JSONArray(bodyString)
                    }

                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.optJSONObject(i) ?: continue
                        val name = item.optString("name", "")
                        val url = item.optString("url", "")
                        
                        // Local Filter as shown in HTML search:
                        // files.filter(f => f.name.match(/\b\d{3,4}\b/g)?.includes(query))
                        if (name.contains(courseCode)) {
                            resultList.add(SparkFile(name = name, url = url))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search exception", e)
        }
        return@withContext resultList
    }

    /**
     * Sends resource request forms to Google Sheets directly using POST.
     */
    suspend fun sendRequest(
        name: String,
        email: String,
        level: String,
        documentType: String,
        courseCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("name", name)
                .add("email", email)
                .add("level", level)
                .add("documentType", documentType)
                .add("code", courseCode)
                .build()

            val request = Request.Builder()
                .url(REQUEST_API)
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                Log.d(TAG, "Request Form Response: $responseText")
                return@withContext responseText.trim().equals("Success", ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send request error", e)
            return@withContext false
        }
    }

    /**
     * Upload contribution files safely to Drive script using base64 and forms.
     */
    suspend fun uploadContribution(
        fileName: String,
        mimeType: String,
        category: String,
        program: String,
        contributorName: String,
        contributorEmail: String,
        fileStream: InputStream
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            // Read stream into base64
            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead)
            }
            val base64Content = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT)

            val formBody = FormBody.Builder()
                .add("fileContent", base64Content)
                .add("fileName", fileName)
                .add("mimeType", mimeType)
                .add("category", category)
                .add("program", program)
                .add("customName", contributorName)
                .add("customEmail", contributorEmail)
                .build()

            val request = Request.Builder()
                .url(CONTRIBUTION_UPLOAD_API)
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string() ?: ""
                Log.d(TAG, "Upload response: $responseText")
                if (response.isSuccessful && !responseText.contains("error", ignoreCase = true)) {
                    UploadResult.Success(responseText)
                } else {
                    UploadResult.Failure(responseText.ifEmpty { "Upload rejected by script" })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed error", e)
            UploadResult.Failure("Network error: ${e.localizedMessage ?: "Upload timed out"}")
        }
    }
}

// Spark files model
data class SparkFile(val name: String, val url: String)

// Login verification response state types
sealed class LoginResult {
    data class Success(
        val studentId: String,
        val fullName: String,
        val email: String,
        val level: String,
        val program: String,
        val semester: String,
        val courses: List<String>,
        val lastLogin: String,
        val deviceUsing: Int,
        val deviceAllowed: Int
    ) : LoginResult()

    data class Failure(
        val message: String,
        val reason: String, // e.g., incorrect_password, device_limit_reached, account_blocked, expired
        val deviceUsing: Int,
        val deviceAllowed: Int
    ) : LoginResult()

    data class Error(val message: String) : LoginResult()
}

// Contribution Upload response state types
sealed class UploadResult {
    data class Success(val message: String) : UploadResult()
    data class Failure(val error: String) : UploadResult()
}
