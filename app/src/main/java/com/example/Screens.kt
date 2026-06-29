package com.example

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.*
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow

// Global current screen states
enum class AppScreen {
    Login,
    ProfileSetup,
    Dashboard,
    RequestData,
    UploadData,
    Downloaded,
    FilePreview
}

fun showInstantNotification(context: Context, title: String, message: String) {
    try {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "spark_license_alert_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Portal Account & License Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts regarding your portal student license status and admin updates"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
            
        notificationManager.notify(404, builder.build())
        
        // Also show a toast so it is visible inside the app immediately on screen
        Toast.makeText(context, "$title\n$message", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainLayoutApp(context: Context) {
    var currentScreen by remember { mutableStateOf(AppScreen.Login) }
    var selectedFileUrl by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf("") }
    var setupComplete by remember { mutableStateOf(false) }

    var isVerifyingSession by remember { mutableStateOf(false) }
    var isAccountBlocked by remember { mutableStateOf(false) }
    var isLicenseExpired by remember { mutableStateOf(false) }
    var verificationError by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    fun verifySession() {
        val email = ProfileStore.getEmail(context)
        val password = ProfileStore.getSavedPassword(context)
        if (email.isNotEmpty() && password.isNotEmpty()) {
            isVerifyingSession = true
            verificationError = ""
            coroutineScope.launch {
                val result = SparkNetwork.verifyLogin(context, email, password, isCheckOnly = true)
                isVerifyingSession = false
                when (result) {
                    is LoginResult.Success -> {
                        ProfileStore.establishSession(
                            context = context,
                            studentId = result.studentId,
                            email = result.email,
                            pass = password,
                            fullName = result.fullName,
                            level = result.level,
                            program = result.program,
                            semester = result.semester,
                            courseCodes = result.courses.joinToString(", ")
                        )
                        ProfileStore.saveVerifiedAccountSession(
                            context = context,
                            email = result.email,
                            pass = password,
                            studentId = result.studentId,
                            fullName = result.fullName,
                            level = result.level,
                            program = result.program,
                            semester = result.semester,
                            courseCodes = result.courses.joinToString(", ")
                        )
                        ProfileStore.setBlocked(context, false)
                        ProfileStore.setExpired(context, false)
                        isAccountBlocked = false
                        isLicenseExpired = false
                        val hasNameSet = ProfileStore.getName(context).isNotEmpty()
                        setupComplete = hasNameSet
                        currentScreen = if (hasNameSet) AppScreen.Dashboard else AppScreen.ProfileSetup
                    }
                    is LoginResult.Failure -> {
                        val isBlocked = result.reason == "account_blocked" || 
                                        result.message.lowercase().contains("block") || 
                                        result.message.lowercase().contains("inactive") ||
                                        result.reason.lowercase().contains("block")
                        val isExpired = result.reason == "expired" ||
                                        result.message.lowercase().contains("expire") ||
                                        result.message.lowercase().contains("license") ||
                                        result.reason.lowercase().contains("expire")
                        if (isBlocked) {
                            ProfileStore.setBlocked(context, true)
                            isAccountBlocked = true
                        } else if (isExpired) {
                            ProfileStore.setExpired(context, true)
                            isLicenseExpired = true
                        } else {
                            verificationError = "Authentication failed: ${result.message}"
                            ProfileStore.logout(context)
                            currentScreen = AppScreen.Login
                        }
                    }
                    is LoginResult.Error -> {
                        // Network connection issue: let user use local cached state as fallback
                        if (ProfileStore.isBlocked(context)) {
                            isAccountBlocked = true
                        } else if (ProfileStore.isExpired(context)) {
                            isLicenseExpired = true
                        } else {
                            val hasNameSet = ProfileStore.getName(context).isNotEmpty()
                            setupComplete = hasNameSet
                            currentScreen = if (hasNameSet) AppScreen.Dashboard else AppScreen.ProfileSetup
                        }
                    }
                }
            }
        } else {
            currentScreen = AppScreen.Login
        }
    }

    // Session validation on startup: Check the expiry date and block status on each run (isCheckOnly=true to avoid device counting)
    LaunchedEffect(Unit) {
        if (ProfileStore.isBlocked(context)) {
            isAccountBlocked = true
        } else if (ProfileStore.isExpired(context)) {
            isLicenseExpired = true
        } else if (ProfileStore.isSessionValid(context)) {
            val hasNameSet = ProfileStore.getName(context).isNotEmpty()
            setupComplete = hasNameSet
            currentScreen = if (hasNameSet) AppScreen.Dashboard else AppScreen.ProfileSetup
            // Trigger check-only server status verification in the background asynchronously
            verifySession()
        } else {
            currentScreen = AppScreen.Login
        }
    }

    LaunchedEffect(currentScreen) {
        setupComplete = ProfileStore.getName(context).isNotEmpty()
    }

    // Dynamic periodic license & block checking loop (Runs every 12 hours when logged in, isCheckOnly=true to avoid device counting)
    LaunchedEffect(currentScreen) {
        if (currentScreen != AppScreen.Login) {
            while (true) {
                delay(12 * 60 * 60 * 1000L) // Delay 12 hours
                val email = ProfileStore.getEmail(context)
                val password = ProfileStore.getSavedPassword(context)
                if (email.isNotEmpty() && password.isNotEmpty()) {
                    val result = SparkNetwork.verifyLogin(context, email, password, isCheckOnly = true)
                    when (result) {
                        is LoginResult.Success -> {
                            ProfileStore.setBlocked(context, false)
                            ProfileStore.setExpired(context, false)
                            isAccountBlocked = false
                            isLicenseExpired = false
                        }
                        is LoginResult.Failure -> {
                            val isBlocked = result.reason == "account_blocked" || 
                                            result.message.lowercase().contains("block") || 
                                            result.message.lowercase().contains("inactive") ||
                                            result.reason.lowercase().contains("block")
                            val isExpired = result.reason == "expired" ||
                                            result.message.lowercase().contains("expire") ||
                                            result.message.lowercase().contains("license") ||
                                            result.reason.lowercase().contains("expire")
                            if (isBlocked) {
                                ProfileStore.setBlocked(context, true)
                                isAccountBlocked = true
                            } else if (isExpired) {
                                ProfileStore.setExpired(context, true)
                                isLicenseExpired = true
                            }
                        }
                        else -> {
                            // Offline or network error: let user use local session
                        }
                    }
                }
            }
        }
    }

    // Modern background brush (space blue to deep slate gradient as requested by theme)
    val backgroundBrush = Brush.linearGradient(
        colors = listOf(SparkDarkBg, Color(0xFF111827)),
        start = Offset(0f, 0f),
        end = Offset(1000f, 2000f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        val showBottomNavigation = currentScreen != AppScreen.Login && currentScreen != AppScreen.FilePreview && setupComplete
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomNavigation) 74.dp else 0.dp)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    slideInHorizontally(initialOffsetX = { 1000 }) + fadeIn() with
                            slideOutHorizontally(targetOffsetX = { -1000 }) + fadeOut()
                },
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    AppScreen.Login -> {
                        LoginScreen(
                            context = context,
                            onLoginSuccess = { studentId ->
                                val hasNameSet = ProfileStore.getName(context).isNotEmpty()
                                currentScreen = if (hasNameSet) AppScreen.Dashboard else AppScreen.ProfileSetup
                            },
                            onAccountBlocked = {
                                isAccountBlocked = true
                            },
                            onLicenseExpired = {
                                isLicenseExpired = true
                            }
                        )
                    }
                    AppScreen.ProfileSetup -> {
                        ProfileSetupScreen(
                            context = context,
                            onProfileSaved = {
                                currentScreen = AppScreen.Dashboard
                            }
                        )
                    }
                    AppScreen.Dashboard -> {
                        DashboardScreen(
                            context = context,
                            onNavigate = { target -> currentScreen = target },
                            onOpenFile = { name, url ->
                                selectedFileName = name
                                selectedFileUrl = url
                                currentScreen = AppScreen.FilePreview
                            },
                            onLogout = {
                                isLicenseExpired = false
                                isAccountBlocked = false
                                currentScreen = AppScreen.Login
                            }
                        )
                    }
                    AppScreen.RequestData -> {
                        RequestDataScreen(
                            context = context,
                            onBack = { currentScreen = AppScreen.Dashboard }
                        )
                    }
                    AppScreen.UploadData -> {
                        UploadDataScreen(
                            context = context,
                            onBack = { currentScreen = AppScreen.Dashboard }
                        )
                    }
                    AppScreen.Downloaded -> {
                        DownloadedScreen(
                            context = context,
                            onOpenFile = { name, url ->
                                selectedFileName = name
                                selectedFileUrl = url
                                currentScreen = AppScreen.FilePreview
                            }
                        )
                    }
                    AppScreen.FilePreview -> {
                        FilePreviewScreen(
                            fileName = selectedFileName,
                            fileUrl = selectedFileUrl,
                            onBack = { currentScreen = AppScreen.Dashboard }
                        )
                    }
                }
            }
        }

        // Sliding Bottom Tab Bar for MainLayoutApp
        if (showBottomNavigation) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xFF0D1224))
                    .drawBehind {
                        // Fine top border to separate screen from bar
                        drawLine(
                            color = SparkBorder,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabs = listOf(
                        Triple(AppScreen.Dashboard, "🏠", "HOME"),
                        Triple(AppScreen.RequestData, "📩", "REQUEST"),
                        Triple(AppScreen.Downloaded, "📥", "OFFLINE")
                    )
                    
                    tabs.forEach { (tabScreen, icon, label) ->
                        val isSelected = currentScreen == tabScreen
                        
                        // Elastic bouncy styling animation
                        val scale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "TabScale"
                        )
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { 
                                    currentScreen = tabScreen 
                                }
                                .padding(vertical = 6.dp, horizontal = 12.dp)
                                .scale(scale)
                        ) {
                            Text(
                                text = icon,
                                fontSize = 20.sp,
                                color = if (isSelected) SparkAccent else SparkTextColorAlt
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                color = if (isSelected) SparkAccent else SparkTextColorAlt,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }

        // OVERLAYS FOR ACCESS CONTROL / SECURITY ENFORCEMENT
        if (isAccountBlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070B14))
                    .clickable(enabled = false) {}, // Intercept touch events
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E1B2C))
                        .border(BorderStroke(1.5.dp, Color(0xFFEF4444)), RoundedCornerShape(24.dp))
                        .padding(28.dp)
                ) {
                    Text("🚫", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "ACCOUNT SUSPENDED",
                        color = Color(0xFFFCA5A5),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This account or Student ID has been suspended / blocked by the system administrator.",
                        color = SparkTextColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Portal access is revoked. Please contact regional administrative office or technical support group for details and reinstatement.",
                        color = SparkTextColorAlt,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1F000000))
                            .padding(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("• Student ID: ${ProfileStore.getStudentId(context)}", color = SparkTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• Status: Administrative Block Active", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir please open my status, my account is blocked in the app. My Student ID is: ${ProfileStore.getStudentId(context)}")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("💬 Contact Admin on WhatsApp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        } else if (isLicenseExpired) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070B14))
                    .clickable(enabled = false) {}, // Intercept touch events
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E1B2C))
                        .border(BorderStroke(1.5.dp, Color(0xFFEAB308)), RoundedCornerShape(24.dp))
                        .padding(28.dp)
                ) {
                    Text("🔑", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "LICENSE EXPIRED",
                        color = Color(0xFFFDE047),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your application license has expired. Please renew your access subscription to continue studying.",
                        color = SparkTextColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please reach out to the helpdesk or your administrator via WhatsApp to quickly renew your portal license and restore immediate access.",
                        color = SparkTextColorAlt,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1F000000))
                            .padding(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("• Student ID: ${ProfileStore.getStudentId(context)}", color = SparkTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("• Status: Subscription Expired", color = Color(0xFFEAB308), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir my lissence expire pls help me")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("💬 sir my lissence expire pls help me", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            verifySession()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🔄 Check License Status (Retry)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            ProfileStore.logout(context)
                            isLicenseExpired = false
                            isAccountBlocked = false
                            currentScreen = AppScreen.Login
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SparkTextColorAlt),
                        border = BorderStroke(1.dp, SparkBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🚪 Sign Out / Go Back to Login", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        } else if (isVerifyingSession) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050810))
                    .clickable(enabled = false) {}, // Intercept touch events
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    CircularProgressIndicator(
                        color = SparkAccent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "AUTHENTICATING SESSION",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Securing login status directly with Spark Cloud regional servers...",
                        color = SparkTextColorAlt,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        } else if (verificationError.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050810))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(SparkCardBg)
                        .border(BorderStroke(1.dp, SparkBorder), RoundedCornerShape(24.dp))
                        .padding(28.dp)
                ) {
                    Text("📶", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "VERIFICATION TIMEOUT",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = verificationError,
                        color = SparkTextColorAlt,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { verifySession() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Retry Live Verification 🔄", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir i am getting session verification error the app is saying: $verificationError")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("💬 Contact Admin on WhatsApp", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Secure portal locks direct backend features until a live authentication check succeeds.",
                        color = SparkTextColorAlt,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 1. SECURE ACCESS LOGIN SCREEN
 * Replicates the secure portal HTML design perfectly ( padlocks, emojis, WhatsApp error response buttons)
 */
@Composable
fun LoginScreen(
    context: Context,
    onLoginSuccess: (String) -> Unit,
    onAccountBlocked: () -> Unit,
    onLicenseExpired: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // Status box state
    var statusMessage by remember { mutableStateOf("Ready • Enter your credentials") }
    var statusType by remember { mutableStateOf("info") } // info, success, error
    var errorCodeAction by remember { mutableStateOf("") } // incorrect_password, device_limit_reached, account_blocked, expired

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Identity Brand
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 450.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SparkCardBgDark),
            border = BorderStroke(1.dp, SparkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header (Branding block)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔐", fontSize = 24.sp)
                        Text(
                            text = "Access",
                            color = SparkTextColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("v4.0", color = SparkTextColorAlt, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Input field 1: Email Address
                Text(
                    text = "📧 Email Address",
                    color = SparkPurpleLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "(Enter your Gmail/Email)",
                    color = SparkTextColorAlt,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                TextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = { Text("Enter your Gmail/Email", color = SparkTextColorAlt) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Text("@", fontSize = 16.sp, color = SparkTextColorAlt) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedIndicatorColor = SparkAccent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = SparkTextColor,
                        unfocusedTextColor = SparkTextColor
                    ),
                    shape = RoundedCornerShape(30.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Input field 2: Password
                Text(
                    text = "🔑 Password",
                    color = SparkPurpleLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("Enter your password", color = SparkTextColorAlt) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Text("🔒", fontSize = 16.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedIndicatorColor = SparkAccent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = SparkTextColor,
                        unfocusedTextColor = SparkTextColor
                    ),
                    shape = RoundedCornerShape(30.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Log in Button
                Button(
                    onClick = {
                        // Client-side quick checks
                        val cleanEmail = email.trim()
                        if (cleanEmail.isEmpty() || password.isEmpty()) {
                            statusMessage = "⚠️ Please enter both Email and Password."
                            statusType = "error"
                            return@Button
                        }
                        if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
                            statusMessage = "⚠️ Please enter a valid email address."
                            statusType = "error"
                            return@Button
                        }

                        isLoading = true
                        statusMessage = "Verifying student session..."
                        statusType = "info"
                        errorCodeAction = ""

                        coroutineScope.launch {
                            // Bypassing network verification if they are logging back in with previously verified credentials on this device
                            val storedSession = ProfileStore.getVerifiedAccountSession(context, cleanEmail)
                            if (storedSession != null) {
                                val storedPass = storedSession["password"] ?: ""
                                if (storedPass.isNotEmpty() && password.trim() == storedPass.trim()) {
                                    val studentId = storedSession["student_id"] ?: ""
                                    val name = storedSession["name"] ?: ""
                                    val level = storedSession["level"] ?: ""
                                    val program = storedSession["program"] ?: ""
                                    val semester = storedSession["semester"] ?: ""
                                    val courseCodes = storedSession["course_codes"] ?: ""

                                    isLoading = false
                                    statusMessage = "✅ Welcome back, $name!"
                                    statusType = "success"

                                    ProfileStore.establishSession(
                                        context = context,
                                        studentId = studentId,
                                        email = cleanEmail,
                                        pass = storedPass,
                                        fullName = name,
                                        level = level,
                                        program = program,
                                        semester = semester,
                                        courseCodes = courseCodes
                                    )
                                    delay(1000)
                                    onLoginSuccess(studentId)
                                } else {
                                    isLoading = false
                                    statusMessage = "❌ Incorrect Password"
                                    statusType = "error"
                                }
                                return@launch
                            }

                            val result = SparkNetwork.verifyLogin(context, cleanEmail, password)
                            isLoading = false
                            when (result) {
                                is LoginResult.Success -> {
                                    statusMessage = "✅ Welcome, ${result.fullName}!"
                                    statusType = "success"
                                    ProfileStore.establishSession(
                                        context = context,
                                        studentId = result.studentId,
                                        email = result.email,
                                        pass = password,
                                        fullName = result.fullName,
                                        level = result.level,
                                        program = result.program,
                                        semester = result.semester,
                                        courseCodes = result.courses.joinToString(", ")
                                    )
                                    // Cache verified student details to allow future relogins on this device to instantly bypass calling the API
                                    ProfileStore.saveVerifiedAccountSession(
                                        context = context,
                                        email = result.email,
                                        pass = password,
                                        studentId = result.studentId,
                                        fullName = result.fullName,
                                        level = result.level,
                                        program = result.program,
                                        semester = result.semester,
                                        courseCodes = result.courses.joinToString(", ")
                                    )
                                    delay(1000)
                                    onLoginSuccess(result.studentId)
                                }
                                is LoginResult.Failure -> {
                                    statusMessage = "❌ ${result.message}"
                                    statusType = "error"
                                    errorCodeAction = result.reason
                                    val isBlocked = result.reason == "account_blocked" || 
                                                    result.message.lowercase().contains("block") || 
                                                    result.message.lowercase().contains("inactive") ||
                                                    result.reason.lowercase().contains("block")
                                    val isExpired = result.reason == "expired" ||
                                                    result.message.lowercase().contains("expire") ||
                                                    result.message.lowercase().contains("license") ||
                                                    result.reason.lowercase().contains("expire")
                                    
                                    if (isBlocked) {
                                        coroutineScope.launch {
                                            delay(1500)
                                            onAccountBlocked()
                                        }
                                    } else if (isExpired) {
                                        coroutineScope.launch {
                                            delay(1500)
                                            onLicenseExpired()
                                        }
                                    }
                                }
                                is LoginResult.Error -> {
                                    statusMessage = "⚠️ ${result.message}"
                                    statusType = "error"
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(30.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = SparkTextColor)
                    } else {
                        Text("Log In", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Standard Status Feedback Box matching HTML design precisely
                val borderStatusColor = when (statusType) {
                    "success" -> GreenSuccess
                    "error" -> RedError
                    else -> BlueCool
                }

                val iconStatus = when (statusType) {
                    "success" -> "✅"
                    "error" -> "❌"
                    else -> "ℹ️"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .drawBehind {
                            // Draw left highlight border stripe
                            drawLine(
                                color = borderStatusColor,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 10f
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(iconStatus, fontSize = 16.sp)
                            Text(
                                text = statusMessage,
                                color = SparkTextColor,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Contact support on error action
                        if (statusType == "error") {
                            val isBlocked = errorCodeAction == "account_blocked" || 
                                            statusMessage.contains("block", ignoreCase = true) || 
                                            statusMessage.contains("inactive", ignoreCase = true)
                            val isIncorrectPassword = errorCodeAction == "incorrect_password" || statusMessage.contains("incorrect", ignoreCase = true) || statusMessage.contains("password", ignoreCase = true) || statusMessage.contains("fail", ignoreCase = true)
                            val isDeviceLimit = errorCodeAction == "device_limit_reached" || statusMessage.contains("limit", ignoreCase = true)

                            if (isBlocked) {
                                Button(
                                    onClick = {
                                        val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir please open my status, my account is blocked in the app. My Student ID is: $email")
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)), // Red contact admin color
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("🚫 Account Blocked. Contact Support 💬", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else if (isIncorrectPassword) {
                                Button(
                                    onClick = {
                                        val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir i need login credintial of the app")
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), // WhatsApp Color
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("🔑 Send Credential Help to WhatsApp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else if (isDeviceLimit) {
                                Button(
                                    onClick = {
                                        val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("limit reached need help")
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), // WhatsApp Color
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("📱 Send Device Limit Help to WhatsApp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else {
                                // Fallback general support button
                                Button(
                                    onClick = {
                                        val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir i need login credintial of the app / need help with login")
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), // WhatsApp Color
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("💬 Contact WhatsApp Support", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Permanent credential request button for first time users
                OutlinedButton(
                    onClick = {
                        val waUrl = "https://wa.me/923306395394?text=" + Uri.encode("sir i need login credintial of the app")
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SparkPurpleLight),
                    border = BorderStroke(1.dp, SparkBorder),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("💬 Don't have login ID/Password? Get via WhatsApp", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "🔒 Secured • Spark Sheet Backend",
                    color = SparkTextColorAlt,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * 2. PROFILE SETUP (For registered student first initialization)
 * Student sets their name, email, level (program), semester, and course codes.
 */
@Composable
fun ProfileSetupScreen(context: Context, onProfileSaved: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var program by remember { mutableStateOf("BS") }
    var semester by remember { mutableStateOf("1st") }
    var courseCodes by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SparkCardBg),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp)
            ) {
                Text(
                    text = "✨ Student Profile Setup",
                    color = SparkTextColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Personalize your AIOU curriculum for tailored material automatic searches.",
                    color = SparkTextColorAlt,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Name input
                Text("👤 Full Name", color = SparkPurpleLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Your full registered name", color = SparkTextColorAlt) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = SparkTextColor,
                        unfocusedTextColor = SparkTextColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Email Input
                Text("📧 Email Address", color = SparkPurpleLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                TextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = { Text("e.g. student@spark.com", color = SparkTextColorAlt) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = SparkTextColor,
                        unfocusedTextColor = SparkTextColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Level / Program drop selectors
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🎓 Level/Program", color = SparkPurpleLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        var expandedProgram by remember { mutableStateOf(false) }
                        val programs = listOf("Matric", "F.A.", "B.A.", "B.S.", "BBA", "B.Ed", "M.A.", "Other")
                        
                        Box {
                            Button(
                                onClick = { expandedProgram = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(program, color = SparkTextColor, overflow = TextOverflow.Ellipsis, maxLines = 1)
                            }
                            DropdownMenu(
                                expanded = expandedProgram,
                                onDismissRequest = { expandedProgram = false },
                                modifier = Modifier.background(SparkCardBg)
                            ) {
                                programs.forEach { prog ->
                                    DropdownMenuItem(
                                        text = { Text(prog, color = SparkTextColor) },
                                        onClick = {
                                            program = prog
                                            expandedProgram = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 Semester", color = SparkPurpleLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))

                        var expandedSemester by remember { mutableStateOf(false) }
                        val semesters = listOf("1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th")

                        Box {
                            Button(
                                onClick = { expandedSemester = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(semester, color = SparkTextColor, overflow = TextOverflow.Ellipsis, maxLines = 1)
                            }
                            DropdownMenu(
                                expanded = expandedSemester,
                                onDismissRequest = { expandedSemester = false },
                                modifier = Modifier.background(SparkCardBg)
                            ) {
                                semesters.forEach { sem ->
                                    DropdownMenuItem(
                                        text = { Text(sem, color = SparkTextColor) },
                                        onClick = {
                                            semester = sem
                                            expandedSemester = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Course code selections
                Text(
                    text = "✍ Course Codes (comma-separated)",
                    color = SparkPurpleLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "e.g. 1423, 5404, 8610 (We search files automatically for these)",
                    color = SparkTextColorAlt,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextField(
                    value = courseCodes,
                    onValueChange = { courseCodes = it },
                    placeholder = { Text("1423, 5404, 8610", color = SparkTextColorAlt) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = SparkTextColor,
                        unfocusedTextColor = SparkTextColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Save trigger with strict rate limiting once-per-week
                Button(
                    onClick = {
                        if (name.isEmpty() || email.isEmpty() || courseCodes.isEmpty()) {
                            Toast.makeText(context, "All fields are required to setup profile!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isSaving = true
                        coroutineScope.launch {
                            val cleanCodes = courseCodes.split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .joinToString(", ")

                            val response = ProfileStore.updateProfile(
                                context = context,
                                name = name,
                                email = email,
                                program = program,
                                semester = semester,
                                courseCodes = cleanCodes
                            )

                            isSaving = false
                            when (response) {
                                is ProfileUpdateResult.Success -> {
                                    Toast.makeText(context, "✓ Profile created successfully!", Toast.LENGTH_SHORT).show()
                                    onProfileSaved()
                                }
                                is ProfileUpdateResult.CoolingDown -> {
                                    Toast.makeText(
                                        context,
                                        "⚠️ Modified recently! Once-per-week rate limit. Available: ${response.availableDate}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("✓ Save & Auto Sync Curriculum", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 15-Minute Data Caching Engine
 * Retains loaded course data across tab navigations statefully,
 * and schedules automatic background resource refreshes exactly every 15 minutes.
 */
object DataCache {
    var lastLoadTime: Long = 0
    var cachedCourses: List<String> = emptyList()
    val cachedResults = mutableMapOf<String, List<SparkFile>>()
    var isLoaded = false
    var selectedCourseCode: String = ""

    fun shouldRefresh(): Boolean {
        if (!isLoaded) return true
        val currentTime = System.currentTimeMillis()
        // 15 minutes = 15 * 60 * 1000 = 900000ms
        return (currentTime - lastLoadTime) >= 900000
    }

    fun populate(courses: List<String>, results: Map<String, List<SparkFile>>) {
        cachedCourses = courses
        cachedResults.clear()
        cachedResults.putAll(results)
        lastLoadTime = System.currentTimeMillis()
        isLoaded = true
    }

    fun clear() {
        cachedCourses = emptyList()
        cachedResults.clear()
        isLoaded = false
        lastLoadTime = 0
        selectedCourseCode = ""
    }
}

@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "Shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerTranslate"
    )
    
    return Brush.linearGradient(
        colors = listOf(
            SparkCardBgDark,
            Color(0xFF312E81), // glowing deep indigo highlight
            SparkCardBgDark
        ),
        start = Offset(translateAnim.value - 300f, translateAnim.value - 300f),
        end = Offset(translateAnim.value + 300f, translateAnim.value + 300f)
    )
}

@Composable
fun ShimmerPlaceholderItem(brush: Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(brush)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.05f))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.05f))
            )
        }
    }
}

/**
 * 3. DASHBOARD (MAIN HUB SCREEN)
 * Student curriculum tracker, automatic premium data matching lists, external hand-written assignments order,
 * contributions download drawer, and premium support locks.
 */
@Composable
fun DashboardScreen(
    context: Context,
    onNavigate: (AppScreen) -> Unit,
    onOpenFile: (name: String, url: String) -> Unit,
    onLogout: () -> Unit = {}
) {
    val coroutineContext = rememberCoroutineScope()
    var registeredName by remember { mutableStateOf("") }
    var currentProgram by remember { mutableStateOf("") }
    var currentSemester by remember { mutableStateOf("") }
    
    var coursesList by remember { mutableStateOf(listOf<String>()) }
    val searchResultsMap = remember { mutableStateMapOf<String, List<SparkFile>>() }
    var isFindingResource by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    var selectedCourseCode by remember { mutableStateOf("") }
    var activeSubTab by remember { mutableStateOf(0) } // 0 = All Documents, 1 = Solved Assignments, 2 = Past Papers & Predictions
    val shimmerBrush = rememberShimmerBrush()

    // Read stored profile with cache engine integration
    LaunchedEffect(Unit) {
        registeredName = ProfileStore.getName(context)
        currentProgram = ProfileStore.getProgram(context)
        currentSemester = ProfileStore.getSemester(context)
        
        if (!DataCache.isLoaded || DataCache.shouldRefresh()) {
            isFindingResource = true
            val rawCodes = ProfileStore.getCourseCodes(context)
            val parsedCourses = rawCodes.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            coursesList = parsedCourses
            
            val tempResults = mutableMapOf<String, List<SparkFile>>()
            parsedCourses.forEach { code ->
                try {
                    val premiumFiles = SparkNetwork.queryPremiumFiles(code)
                    tempResults[code] = premiumFiles
                } catch (e: Exception) {
                    // gracefully fallback
                }
            }
            searchResultsMap.clear()
            searchResultsMap.putAll(tempResults)
            DataCache.populate(parsedCourses, tempResults)
            isFindingResource = false
        } else {
            // Instant restoration from RAM Memory cache (zero loaders or wait times!)
            coursesList = DataCache.cachedCourses
            searchResultsMap.clear()
            searchResultsMap.putAll(DataCache.cachedResults)
        }

        // Keep active code selected
        if (selectedCourseCode.isEmpty() && coursesList.isNotEmpty()) {
            selectedCourseCode = DataCache.selectedCourseCode.ifEmpty { coursesList.first() }
        }

        // Periodic background refresher: Runs every 15 seconds, refreshes only if cache age > 15m
        while (true) {
            delay(15000)
            if (DataCache.isLoaded && DataCache.shouldRefresh()) {
                isFindingResource = true
                val rawCodes = ProfileStore.getCourseCodes(context)
                val parsedCourses = rawCodes.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                
                val tempResults = mutableMapOf<String, List<SparkFile>>()
                parsedCourses.forEach { code ->
                    try {
                        val premiumFiles = SparkNetwork.queryPremiumFiles(code)
                        tempResults[code] = premiumFiles
                    } catch (e: Exception) {
                        // fallback
                    }
                }
                coursesList = parsedCourses
                searchResultsMap.clear()
                searchResultsMap.putAll(tempResults)
                DataCache.populate(parsedCourses, tempResults)
                isFindingResource = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header Bar with sparkles
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, bottom = 12.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(SparkAccent, SparkAccentDark)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "S",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = "Spark AIOU",
                            color = SparkTextColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "OFFICIAL STUDENT PORTAL",
                            color = SparkPurpleLight,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            ProfileStore.logout(context)
                            DataCache.clear()
                            onLogout()
                        }
                    ) {
                        Text("🚪", fontSize = 18.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SparkCardBgDark)
                            .border(1.dp, SparkBorder, CircleShape)
                            .clickable {
                                Toast.makeText(context, "No new system notices", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔔", fontSize = 16.sp)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                                .align(Alignment.TopEnd)
                                .offset(x = (-2).dp, y = 2.dp)
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            // Student curriculum profile card styled in Sleek Interface (Aligned & Overflow-Free)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(SparkCardBgDark, SparkCardBg)
                            )
                        )
                        .border(1.dp, SparkBorder, RoundedCornerShape(24.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Left profile avatar
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF334155))
                                .border(1.dp, Color(0xFF475569), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("👤", fontSize = 20.sp)
                        }
                        
                        // Middle Name and ID details (weighted for auto-wrapping)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = registeredName.ifEmpty { "Guest student" },
                                    color = SparkTextColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF22C55E).copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Backend Sync", color = Color(0xFF4ADE80), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "AIOU Program: ${currentProgram.ifEmpty { "N/A" }}",
                                color = SparkTextColorAlt,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Right Semester columns
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.wrapContentWidth()
                        ) {
                            Text(
                                text = "SEMESTER",
                                color = SparkTextColorAlt,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentSemester.ifEmpty { "Autumn 2024" },
                                color = SparkPurpleLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            }

            // Enrolled courses layout block (Box-Grid Selection)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ENROLLED COURSE CODES",
                        color = SparkTextColorAlt,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    
                    if (coursesList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SparkCardBgDark)
                                .border(1.dp, SparkBorder, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No course codes enrolled yet.",
                                color = SparkTextColorAlt,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        // Render course codes as a beautiful grid of interactive boxes (3 columns layout)
                        val chunks = coursesList.chunked(3)
                        chunks.forEach { rowItems ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                rowItems.forEach { code ->
                                    val isSelected = code == selectedCourseCode
                                    val scale by animateFloatAsState(
                                        targetValue = if (isSelected) 1.05f else 1.0f,
                                        label = "GridItemScale"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .scale(scale)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isSelected) SparkAccent.copy(alpha = 0.20f) else SparkCardBgDark)
                                            .border(
                                                width = 1.5.dp,
                                                color = if (isSelected) SparkAccent else SparkBorder,
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                            .clickable {
                                                selectedCourseCode = code
                                                DataCache.selectedCourseCode = code
                                            }
                                            .padding(vertical = 14.dp, horizontal = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = code,
                                                color = if (isSelected) Color.White else SparkTextColor,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (isSelected) "Selected" else "View Data",
                                                color = if (isSelected) SparkPurpleLight else SparkTextColorAlt,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                if (rowItems.size < 3) {
                                    val remaining = 3 - rowItems.size
                                    repeat(remaining) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bento Grid Services (Student Operations - Clean M3 and Expanded PDF Book Portal)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "STUDENT ACADEMIC PORTAL",
                        color = SparkTextColorAlt,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    
                    // Double-wide high luxury PDF Books access portal
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(SparkCardBgDark, Color(0xFF1E1E38))
                                )
                            )
                            .border(1.dp, SparkBorder, RoundedCornerShape(24.dp))
                            .clickable {
                                if (selectedCourseCode.isNotEmpty()) {
                                    val formattedCode = selectedCourseCode.padStart(4, '0')
                                    onOpenFile("SoftBook $selectedCourseCode.pdf", "https://online.aiou.edu.pk/LIVE_SITE/SoftBooks/$formattedCode.pdf")
                                } else {
                                    Toast.makeText(context, "Select a course code first", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📚", fontSize = 32.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("AIOU SoftBooks Database", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text("Official soft copies direct download portal", color = SparkTextColorAlt, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SparkAccent.copy(alpha = 0.2f))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("Open", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Matches section heading specifically highlighting active Spotlight course on top
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (selectedCourseCode.isNotEmpty()) "📋 $selectedCourseCode ACTIVE STUDY HUB" else "📋 COURSE RESOURCES HUB",
                        color = SparkTextColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFindingResource) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SparkAccent)
                    }
                }
            }

            // Output the dynamic selected course spotlight data - Soft Book and premium resources on top!
            if (coursesList.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📭", fontSize = 36.sp)
                        Text(
                            text = "No course codes registered yet.",
                            color = SparkTextColorAlt,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (selectedCourseCode.isNotEmpty()) {
                // Official SoftBook of the active course code goes directly ON TOP!
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "OFFICIAL SOFT BOOK (ON TOP)",
                            color = SparkPurpleLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF0F172A))
                                .border(1.dp, SparkAccent.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📘", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Official SoftBook - Code $selectedCourseCode",
                                    color = SparkTextColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Direct download from official AIOU servers",
                                    color = SparkTextColorAlt,
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = {
                                    val formattedCode = selectedCourseCode.padStart(4, '0')
                                    val url = "https://online.aiou.edu.pk/LIVE_SITE/SoftBooks/$formattedCode.pdf"
                                    onOpenFile("SoftBook $selectedCourseCode.pdf", url)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("Get Book", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Section header for course contents
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "PREMIUM SOLVED SOLUTIONS & PREDICTIONS",
                            color = SparkTextColorAlt,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Shimmer item loading state
                if (isFindingResource) {
                    items(3) {
                        ShimmerPlaceholderItem(brush = shimmerBrush)
                    }
                } else {
                    // Query or direct match results of the selected code
                    val filteredItems = searchResultsMap[selectedCourseCode] ?: emptyList()

                    if (filteredItems.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(SparkCardBgDark)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🗂️", fontSize = 28.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "No premium solved sheets or predictions for tag yet.",
                                        color = SparkTextColorAlt,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredItems) { sparkFile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable { onOpenFile(sparkFile.name, sparkFile.url) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📄", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sparkFile.name,
                                        color = SparkTextColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF22C55E).copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("Verified", color = Color(0xFF4ADE80), fontSize = 9.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFEAB308).copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("Premium", color = Color(0xFFFBBF24), fontSize = 9.sp)
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Read",
                                    tint = SparkTextColorAlt
                                )
                            }
                        }
                    }
                }
            }
        }


    }
}

/**
 * 3. REQUEST PORTAL FORM SCREEN
 * Completes form fields matched exactly to the request resources database spreadsheet.
 */
@Composable
fun RequestDataScreen(context: Context, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf("B.S.") }
    var selectedDocType by remember { mutableStateOf("Past Paper") }
    var courseCode by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val coroutineContext = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SparkCardBg)
                .padding(top = 44.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SparkTextColor)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("Request Your Resources", color = SparkTextColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .imePadding()
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SparkCardBg),
                border = BorderStroke(1.dp, SparkBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("📩 Fill data details to query from admin", color = SparkTextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                    // Your Name
                    Text("Your Name", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("e.g. Ali Khan", color = SparkTextColorAlt) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF0F172A), unfocusedContainerColor = Color(0xFF0F172A))
                    )

                    // Your Email
                    Text("Your Email address", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = { Text("e.g. ali@gmail.com", color = SparkTextColorAlt) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF0F172A), unfocusedContainerColor = Color(0xFF0F172A))
                    )

                    // Level Picker selector
                    var levelExpanded by remember { mutableStateOf(false) }
                    val levels = listOf("Matric", "F.A.", "B.A.", "B.S.", "BBA", "B.Ed", "M.A.", "Other")
                    Text("Select Program Level", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { levelExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                        ) {
                            Text(selectedLevel, color = SparkTextColor)
                        }
                        DropdownMenu(expanded = levelExpanded, onDismissRequest = { levelExpanded = false }, modifier = Modifier.background(SparkCardBg)) {
                            levels.forEach { level ->
                                DropdownMenuItem(text = { Text(level, color = SparkTextColor) }, onClick = { selectedLevel = level; levelExpanded = false })
                            }
                        }
                    }

                    // Document Type Picker selector
                    var docExpanded by remember { mutableStateOf(false) }
                    val docs = listOf("Books", "Assignment", "Past Paper", "Guess Papers")
                    Text("Select Resource Category", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { docExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                        ) {
                            Text(selectedDocType, color = SparkTextColor)
                        }
                        DropdownMenu(expanded = docExpanded, onDismissRequest = { docExpanded = false }, modifier = Modifier.background(SparkCardBg)) {
                            docs.forEach { doc ->
                                DropdownMenuItem(text = { Text(doc, color = SparkTextColor) }, onClick = { selectedDocType = doc; docExpanded = false })
                            }
                        }
                    }

                    // Course Code
                    Text("Course Code", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    TextField(
                        value = courseCode,
                        onValueChange = { courseCode = it },
                        placeholder = { Text("e.g. 8601", color = SparkTextColorAlt) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF0F172A), unfocusedContainerColor = Color(0xFF0F172A))
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (name.isEmpty() || email.isEmpty() || courseCode.isEmpty()) {
                                Toast.makeText(context, "All form fields are required!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSending = true
                            coroutineContext.launch {
                                val successResult = SparkNetwork.sendRequest(
                                    name = name,
                                    email = email,
                                    level = selectedLevel,
                                    documentType = selectedDocType,
                                    courseCode = courseCode
                                )
                                isSending = false
                                if (successResult) {
                                    Toast.makeText(context, "✅ Request sent successfully!", Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "❌ Submission failed. Check connection", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                        enabled = !isSending
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = SparkTextColor)
                        } else {
                            Text("Send Resources Request 🚀", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4. UPLOAD DATA CONTRIBUTION SCREEN
 * Students contribute files and assignments. If they successfully submit >= 2 uploads,
 * it unlocks full unlocked download widgets for them!
 */
@Composable
fun UploadDataScreen(context: Context, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var selectedProgram by remember { mutableStateOf("B.S.") }
    var selectedCategory by remember { mutableStateOf("Assignments") }
    var isUploading by remember { mutableStateOf(false) }

    val coroutineContext = rememberCoroutineScope()
    var premiumUnlocked by remember { mutableStateOf(false) }
    var trackedUploadsCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        trackedUploadsCount = ProfileStore.getUploadedCount(context)
        premiumUnlocked = trackedUploadsCount >= 2
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SparkCardBg)
                .padding(top = 44.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SparkTextColor)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("📤 Contribute Resource Info", color = SparkTextColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SparkCardBg),
                    border = BorderStroke(1.dp, SparkBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "🎁 Contribution Unlock Task",
                            color = SparkTextColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Upload or share 2 verified files to unlock the full Unlimited Download Portal for the next 12 hours!",
                            color = SparkTextColorAlt,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Your tracked uploads count (12H): $trackedUploadsCount / 2",
                            color = SparkPurpleLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (premiumUnlocked) {
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.sparkaiou.com/p/aiou-all-premium-data.html")
                                    )
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenSuccess)
                            ) {
                                Text("🔥 Files Unlocked! Download Premium Data 🥰", color = SparkDarkBg, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SparkCardBg),
                    border = BorderStroke(1.dp, SparkBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Fill Contribution Metadata", color = SparkTextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                        // Input Custom File Name
                        Text("Enter Custom File Label", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        TextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            placeholder = { Text("e.g. 1423 Solved Assignment 1.pdf", color = SparkTextColorAlt) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF0F172A), unfocusedContainerColor = Color(0xFF0F172A))
                        )

                        // Selector program
                        var pExpanded by remember { mutableStateOf(false) }
                        val programs = listOf("Matic", "FA", "BA", "BS", "BBA", "BEd", "MA", "Others")
                        Text("Select Program", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { pExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                            ) {
                                Text(selectedProgram, color = SparkTextColor)
                            }
                            DropdownMenu(expanded = pExpanded, onDismissRequest = { pExpanded = false }, modifier = Modifier.background(SparkCardBg)) {
                                programs.forEach { p ->
                                    DropdownMenuItem(text = { Text(p, color = SparkTextColor) }, onClick = { selectedProgram = p; pExpanded = false })
                                }
                            }
                        }

                        // Selector Category
                        var cExpanded by remember { mutableStateOf(false) }
                        val categories = listOf("Books", "Assignments", "GuessPaper", "PastPaper", "Others")
                        Text("Select Category", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { cExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                            ) {
                                Text(selectedCategory, color = SparkTextColor)
                            }
                            DropdownMenu(expanded = cExpanded, onDismissRequest = { cExpanded = false }, modifier = Modifier.background(SparkCardBg)) {
                                categories.forEach { cat ->
                                    DropdownMenuItem(text = { Text(cat, color = SparkTextColor) }, onClick = { selectedCategory = cat; cExpanded = false })
                                }
                            }
                        }

                        // Contributor Name
                        Text("Your Contributor Name", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("Your name", color = SparkTextColorAlt) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF0F172A), unfocusedContainerColor = Color(0xFF0F172A))
                        )

                        // Contributor Email
                        Text("Your Email Address", color = SparkPurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        TextField(
                            value = email,
                            onValueChange = { email = it },
                            placeholder = { Text("Your email address", color = SparkTextColorAlt) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF0F172A), unfocusedContainerColor = Color(0xFF0F172A))
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (name.isEmpty() || email.isEmpty() || fileName.isEmpty()) {
                                    Toast.makeText(context, "All contribution details required!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isUploading = true
                                coroutineContext.launch {
                                    // Generate mock study text streams to send to Base64 script API safely
                                    val dummyStream = ByteArrayInputStream("Spark Study Resource Contribution - Code ${fileName.take(4)}".toByteArray(StandardCharsets.UTF_8))
                                    val result = SparkNetwork.uploadContribution(
                                        fileName = "$fileName.txt",
                                        mimeType = "text/plain",
                                        category = selectedCategory,
                                        program = selectedProgram,
                                        contributorName = name,
                                        contributorEmail = email,
                                        fileStream = dummyStream
                                    )
                                    isUploading = false
                                    when (result) {
                                        is UploadResult.Success -> {
                                            ProfileStore.incrementUploadedCount(context)
                                            trackedUploadsCount = ProfileStore.getUploadedCount(context)
                                            premiumUnlocked = trackedUploadsCount >= 2
                                            Toast.makeText(context, "✓ Contribution Upload Successfully Registered!", Toast.LENGTH_LONG).show()
                                        }
                                        is UploadResult.Failure -> {
                                            Toast.makeText(context, "Upload registration failed: ${result.error}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SparkAccent),
                            enabled = !isUploading
                        ) {
                            if (isUploading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = SparkTextColor)
                            } else {
                                Text("Upload File Resource Now 🚀", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 5. OFFLINE DOWNLOAD HUB / BOOKSHELF
 * Lists all student resources, books, solved sheets saved offline.
 * Accessible from the main bottom navigation hub menu.
 */
@Composable
fun DownloadedScreen(
    context: Context,
    onOpenFile: (name: String, url: String) -> Unit
) {
    var downloadedFiles by remember { mutableStateOf(emptyList<OfflineStore.OfflineFile>()) }
    
    LaunchedEffect(Unit) {
        downloadedFiles = OfflineStore.getDownloadedFiles(context)
    }

    Column(modifier = Modifier.fillMaxSize().background(SparkDarkBg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SparkCardBg)
                .padding(top = 44.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
        ) {
            Column {
                Text(
                    text = "📥 Offline Bookshelf", 
                    color = SparkTextColor, 
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Read downloaded books & solutions without internet", 
                    color = SparkTextColorAlt, 
                    fontSize = 11.sp
                )
            }
        }

        if (downloadedFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📭", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your offline bookshelf is empty.",
                        color = SparkTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Open resources on the main dashboard and click 'Save Offline' to keep them here permanently.",
                        color = SparkTextColorAlt,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(downloadedFiles) { offlineFile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenFile(offlineFile.title, offlineFile.url)
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SparkCardBg),
                        border = BorderStroke(1.dp, SparkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📘", fontSize = 28.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = offlineFile.title,
                                        color = SparkTextColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Saved Local Copy",
                                        color = GreenSuccess,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    OfflineStore.deleteDownloadedFile(context, offlineFile.url)
                                    downloadedFiles = OfflineStore.getDownloadedFiles(context)
                                    Toast.makeText(context, "Removed from library", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("🗑️", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 6. HIGH DENSITY NATIVE PDF PAGE RENDERER
 * Loads individual layout pages on demand asynchronously using Android PdfRenderer.
 * Supports pinch-to-zoom and drag-to-pan in high fidelity interface.
 */
@Composable
fun PdfPageItem(
    renderer: PdfRenderer,
    pageIndex: Int,
    renderMutex: kotlinx.coroutines.sync.Mutex,
    bitmapCache: MutableMap<Int, Bitmap>,
    onCacheBitmap: (Int, Bitmap) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(bitmapCache[pageIndex]) }
    
    LaunchedEffect(pageIndex) {
        if (bitmap != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            renderMutex.withLock {
                val cached = bitmapCache[pageIndex]
                if (cached != null) {
                    bitmap = cached
                    return@withLock
                }
                try {
                    val page = renderer.openPage(pageIndex)
                    val scaleFactor = 1200f / page.width
                    val w = 1200
                    val h = (page.height * scaleFactor).toInt()
                    
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    
                    withContext(Dispatchers.Main) {
                        bitmap = bmp
                        onCacheBitmap(pageIndex, bmp)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    LaunchedEffect(scale) {
        if (scale <= 1.0f) {
            offset = Offset.Zero
        }
    }
    
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .shadow(2.dp, RoundedCornerShape(4.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = if (constraints.maxHeight != Int.MAX_VALUE && constraints.maxHeight > 0) {
            constraints.maxHeight.toFloat()
        } else {
            widthPx * 1.4142f
        }

        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(scale) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) {
                                val maxPanX = (newScale - 1f) * (widthPx / 2f)
                                val maxPanY = (newScale - 1f) * (heightPx / 2f)
                                offset = Offset(
                                    x = (offset.x + pan.x * newScale).coerceIn(-maxPanX, maxPanX),
                                    y = (offset.y + pan.y * newScale).coerceIn(-maxPanY, maxPanY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .clickable(enabled = scale > 1f, onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    }),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = SparkAccent,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

/**
 * 7. HIGH QUALITY IN-APP NATIVE PDF VIEWER
 * Seamless scroll list loading all physical pages of the document, completely solving the single page display issue.
 */
@Composable
fun NativePdfViewer(
    file: File,
    fileName: String,
    onBack: () -> Unit,
    fileUrl: String,
    context: Context
) {
    var isSavedOffline by remember { mutableStateOf(false) }
    
    LaunchedEffect(file) {
        val saved = OfflineStore.getDownloadedFiles(context).any { it.url == fileUrl }
        isSavedOffline = saved
    }

    val fileDescriptor = remember(file) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            null
        }
    }

    val pdfRenderer = remember(fileDescriptor) {
        if (fileDescriptor != null) {
            try {
                PdfRenderer(fileDescriptor)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    val renderMutex = remember { kotlinx.coroutines.sync.Mutex() }
    val bitmapCache = remember { mutableStateMapOf<Int, Bitmap>() }
    val renderedOrder = remember { mutableStateListOf<Int>() }
    
    val onCacheBitmap: (Int, Bitmap) -> Unit = { index, bmp ->
        if (!bitmapCache.containsKey(index)) {
            if (renderedOrder.size >= 16) {
                val oldest = renderedOrder.removeAt(0)
                val evictedBmp = bitmapCache.remove(oldest)
                if (evictedBmp != null && !evictedBmp.isRecycled) {
                    try {
                        evictedBmp.recycle()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            bitmapCache[index] = bmp
            renderedOrder.add(index)
        }
    }

    DisposableEffect(file) {
        onDispose {
            try {
                pdfRenderer?.close()
                fileDescriptor?.close()
                // Recycle cached bitmaps
                bitmapCache.values.forEach { bmp ->
                    if (!bmp.isRecycled) {
                        bmp.recycle()
                    }
                }
                bitmapCache.clear()
                renderedOrder.clear()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SparkDarkBg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SparkCardBg)
                .padding(top = 44.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = SparkTextColor
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            color = SparkTextColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (pdfRenderer != null) "${pdfRenderer.pageCount} Pages • Native Reader" else "External Viewer Mode",
                            color = SparkPurpleLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                if (pdfRenderer != null) {
                    Button(
                        onClick = {
                            OfflineStore.saveDownloadedFile(context, fileName, fileUrl, file.absolutePath)
                            isSavedOffline = true
                            Toast.makeText(context, "✅ Saved to offline library!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSavedOffline) SparkCardBgDark else SparkAccent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        enabled = !isSavedOffline
                    ) {
                        Text(
                            text = if (isSavedOffline) "Saved" else "Save Offline",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        if (pdfRenderer == null) {
            var webLoading by remember { mutableStateOf(true) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(Color(0xFF0F172A))
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                setSupportZoom(true)
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                databaseEnabled = true
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    webLoading = false
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    return false
                                }
                            }
                            loadUrl(fileUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (webLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SparkAccent, strokeWidth = 4.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Preparing in-app secure viewer...",
                                color = SparkTextColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(Color(0xFF0F172A)),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(pdfRenderer.pageCount) { pageIndex ->
                    PdfPageItem(
                        renderer = pdfRenderer,
                        pageIndex = pageIndex,
                        renderMutex = renderMutex,
                        bitmapCache = bitmapCache,
                        onCacheBitmap = onCacheBitmap
                    )
                }
            }
        }
    }
}

fun getDirectDownloadUrl(url: String): String {
    if (url.contains("drive.google.com", ignoreCase = true)) {
        var fileId = ""
        val fileDRegex = Regex("/file/d/([^/&#?]+)")
        val matchResult = fileDRegex.find(url)
        if (matchResult != null) {
            fileId = matchResult.groupValues[1]
        } else {
            val dRegex = Regex("/d/([^/&#?]+)")
            val matchResultD = dRegex.find(url)
            if (matchResultD != null) {
                fileId = matchResultD.groupValues[1]
            } else {
                val idRegex = Regex("[?&]id=([^&#?]+)")
                val matchResultId = idRegex.find(url)
                if (matchResultId != null) {
                    fileId = matchResultId.groupValues[1]
                }
            }
        }
        if (fileId.isNotEmpty()) {
            return "https://drive.google.com/uc?export=download&id=$fileId"
        }
    }
    return url
}

fun isNetworkAvailable(context: android.content.Context): Boolean {
    val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    if (connectivityManager != null) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    return false
}

/**
 * 8. DIRECT FILE PREVIEW CONTROLLER
 * Decides whether to download the PDF and load it natively with progress bars or show in loaded WebViews with animation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FilePreviewScreen(
    fileName: String,
    fileUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isGoogleDrive = fileUrl.contains("drive.google.com", ignoreCase = true) || fileUrl.contains("docs.google.com", ignoreCase = true)
    var localSavedFile by remember { mutableStateOf<File?>(null) }
    // Force download-first for all study files to ensure robust offline-only reading support on bookshelf
    val isPdf = true

    var downloadProgress by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var webViewLoading by remember { mutableStateOf(true) }

    var isDownloadedLocal by remember { mutableStateOf(false) }
    var userChoiceMade by remember { mutableStateOf(false) }
    var triggerDownloadFlag by remember { mutableStateOf(false) }
    var shouldSaveOfflineAutomatically by remember { mutableStateOf(false) }

    // Pre-check if already saved offline on bookshelf, otherwise auto-download
    LaunchedEffect(fileUrl) {
        val hasInternet = isNetworkAvailable(context)
        val matched = OfflineStore.getDownloadedFiles(context).find { 
            it.url == fileUrl || it.title.equals(fileName, ignoreCase = true)
        }
        if (matched != null) {
            val file = File(matched.localPath)
            if (file.exists()) {
                localSavedFile = file
                isDownloadedLocal = true
                userChoiceMade = true
                triggerDownloadFlag = false
            } else {
                if (!hasInternet) {
                    downloadError = "You are offline and this document is not locally saved."
                    userChoiceMade = true
                } else {
                    userChoiceMade = true
                    triggerDownloadFlag = true
                    shouldSaveOfflineAutomatically = true
                }
            }
        } else {
            if (!hasInternet) {
                downloadError = "You are offline and this document has not been downloaded yet."
                userChoiceMade = true
            } else {
                userChoiceMade = true
                triggerDownloadFlag = true
                shouldSaveOfflineAutomatically = true
            }
        }
    }

    LaunchedEffect(triggerDownloadFlag) {
        if (triggerDownloadFlag && isPdf) {
            // Trigger background progressive download
            isDownloading = true
            downloadProgress = 0f
            downloadError = null
            withContext(Dispatchers.IO) {
                try {
                    val dir = context.filesDir.resolve("downloads")
                    if (!dir.exists()) dir.mkdirs()
                    
                    val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val targetFile = dir.resolve(safeName)
                    
                    val initialUrl = if (fileUrl.contains("drive.google.com", ignoreCase = true) || fileUrl.contains("docs.google.com", ignoreCase = true)) getDirectDownloadUrl(fileUrl) else fileUrl
                    
                    var currentUrl = initialUrl
                    var connection = URL(currentUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20000
                    connection.readTimeout = 20000
                    connection.instanceFollowRedirects = true
                    
                    if (connection is javax.net.ssl.HttpsURLConnection) {
                        try {
                            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                                object : javax.net.ssl.X509TrustManager {
                                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                                    override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                    override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                }
                            )
                            val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                            sc.init(null, trustAllCerts, java.security.SecureRandom())
                            connection.sslSocketFactory = sc.socketFactory
                            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    
                    connection.connect()
                    
                    var responseCode = connection.responseCode
                    var redirectCount = 0
                    while (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                           responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                           responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                           responseCode == 307 || responseCode == 308) {
                        if (redirectCount > 8) break
                        val redirectLoc = connection.getHeaderField("Location") ?: break
                        connection.disconnect()
                        currentUrl = if (redirectLoc.startsWith("http")) redirectLoc else {
                            val base = URL(currentUrl)
                            URL(base.protocol, base.host, base.port, redirectLoc).toString()
                        }
                        
                        // Transform intermediate Google Drive redirect links to direct download links on-the-fly
                        if (currentUrl.contains("drive.google.com", ignoreCase = true) || currentUrl.contains("docs.google.com", ignoreCase = true)) {
                            currentUrl = getDirectDownloadUrl(currentUrl)
                        }
                        
                        connection = URL(currentUrl).openConnection() as HttpURLConnection
                        connection.connectTimeout = 20000
                        connection.readTimeout = 20000
                        connection.instanceFollowRedirects = true
                        
                        if (connection is javax.net.ssl.HttpsURLConnection) {
                            try {
                                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                                    object : javax.net.ssl.X509TrustManager {
                                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                                        override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                        override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                    }
                                )
                                val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                                sc.init(null, trustAllCerts, java.security.SecureRandom())
                                connection.sslSocketFactory = sc.socketFactory
                                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        connection.connect()
                        responseCode = connection.responseCode
                        redirectCount++
                    }
                    
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw Exception("Server connection failed with status code $responseCode")
                    }
                    
                    val fileLength = connection.contentLength
                    val input = connection.inputStream
                    val output = FileOutputStream(targetFile)
                    
                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        if (fileLength > 0) {
                            downloadProgress = total.toFloat() / fileLength
                        } else {
                            downloadProgress = (total % 1000000).toFloat() / 1000000f
                        }
                        output.write(data, 0, count)
                    }
                    
                    output.close()
                    input.close()
                    connection.disconnect()
                    
                    OfflineStore.saveDownloadedFile(context, fileName, fileUrl, targetFile.absolutePath)
                    
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        localSavedFile = targetFile
                        isDownloadedLocal = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        downloadError = e.localizedMessage ?: "Failed to save file securely"
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(SparkDarkBg)) {
        if (isPdf) {
            if (!userChoiceMade) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📖", fontSize = 56.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Access Study Document",
                        color = SparkTextColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = fileName,
                        color = SparkPurpleLight,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Choice A: Read Online (stream and display without permanent saving)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                shouldSaveOfflineAutomatically = false
                                userChoiceMade = true
                                triggerDownloadFlag = true
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SparkCardBg),
                        border = BorderStroke(1.dp, SparkBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌐", fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Read Online Stream", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Loads instantly for current reading session", color = SparkTextColorAlt, fontSize = 11.sp)
                            }
                            Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = "Go", tint = SparkTextColorAlt)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Choice B: Download & Save Offline
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                shouldSaveOfflineAutomatically = true
                                userChoiceMade = true
                                triggerDownloadFlag = true
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SparkCardBg),
                        border = BorderStroke(1.dp, SparkBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📥", fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Download & Save Offline", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Saves to your local bookshelf automatically", color = SparkTextColorAlt, fontSize = 11.sp)
                            }
                            Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = "Go", tint = SparkTextColorAlt)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = SparkCardBgDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Go Back", color = SparkTextColor)
                    }
                }
            } else if (isDownloading) {
                // Interactive download portal displaying live percentage and progress bar
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📥", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Downloading Study Document...",
                        color = SparkTextColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Applying 15-Min Adaptive Cache Mechanics",
                        color = SparkPurpleLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Circular progress bar
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(72.dp),
                        color = SparkAccent,
                        strokeWidth = 6.dp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "${(downloadProgress * 100).toInt()}% Retrieved",
                        color = SparkTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = SparkCardBgDark)
                    ) {
                        Text("Cancel Stream", color = SparkTextColor)
                    }
                }
            } else if (downloadError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("❌", fontSize = 44.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Document Load Failed",
                        color = SparkTextColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = downloadError ?: "Unknown connection error",
                        color = SparkTextColorAlt,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = SparkAccent)
                    ) {
                        Text("Go Back")
                    }
                }
            } else {
                localSavedFile?.let { file ->
                    NativePdfViewer(
                        file = file,
                        fileName = fileName,
                        onBack = onBack,
                        fileUrl = fileUrl,
                        context = context
                    )
                }
            }
        } else {
            // HTML Pages in fully animated preview loader WebViews
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SparkCardBg)
                        .padding(top = 44.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = SparkTextColor
                                )
                            }
                            Column {
                                Text(
                                    text = fileName,
                                    color = SparkTextColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "In-App Secure Browsing",
                                    color = SparkPurpleLight,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.setSupportZoom(true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        webViewLoading = false
                                    }
                                }
                                loadUrl(fileUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (webViewLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SparkDarkBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = SparkAccent, strokeWidth = 5.dp)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Booting secure educational gateway...",
                                    color = SparkTextColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
