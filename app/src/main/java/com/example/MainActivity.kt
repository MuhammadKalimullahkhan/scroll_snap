package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.data.SavedScreenshotItem
import com.example.data.ScreenshotSaver
import com.example.ui.CaptureTrampolineActivity
import com.example.ui.theme.ScrollSnapTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REQUEST_PERMISSIONS = "extra_request_permissions"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ScrollSnapTheme {
                MainScreen(
                    onStartCapture = {
                        val trampolineIntent = Intent(this, CaptureTrampolineActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(trampolineIntent)
                        // Send MainActivity to back so user is immediately over their target app
                        moveTaskToBack(true)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onStartCapture: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val saver = remember { ScreenshotSaver(context) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var showOverlayDisclosureDialog by remember { mutableStateOf(false) }
    var showNotificationRationaleDialog by remember { mutableStateOf(false) }

    var savedScreenshots by remember { mutableStateOf<List<SavedScreenshotItem>>(emptyList()) }

    // Re-check permissions when returning to activity
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        coroutineScope.launch {
            savedScreenshots = saver.querySavedScreenshots()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenShare,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "ScrollSnap",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Smart Scrolling Screenshots",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Capture") },
                    modifier = Modifier.testTag("tab_capture")
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        coroutineScope.launch {
                            savedScreenshots = saver.querySavedScreenshots()
                        }
                    },
                    text = { Text("Gallery (${savedScreenshots.size})") },
                    modifier = Modifier.testTag("tab_gallery")
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Guide & Privacy") },
                    modifier = Modifier.testTag("tab_guide")
                )
            }

            when (selectedTabIndex) {
                0 -> CaptureTab(
                    hasOverlay = hasOverlayPermission,
                    hasNotification = hasNotificationPermission,
                    onRequestOverlay = { showOverlayDisclosureDialog = true },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartCapture = onStartCapture
                )
                1 -> GalleryTab(
                    screenshots = savedScreenshots,
                    onRefresh = {
                        coroutineScope.launch {
                            savedScreenshots = saver.querySavedScreenshots()
                        }
                    },
                    onOpen = { item ->
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(viewIntent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No gallery app found to open image", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShare = { item ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, item.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Screenshot"))
                    },
                    onDelete = { item ->
                        try {
                            context.contentResolver.delete(item.uri, null, null)
                            coroutineScope.launch {
                                savedScreenshots = saver.querySavedScreenshots()
                            }
                            Toast.makeText(context, "Screenshot deleted", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Unable to delete screenshot", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                2 -> GuideAndPrivacyTab()
            }
        }
    }

    // Prominent In-App Disclosure Dialog for SYSTEM_ALERT_WINDOW (Overlay Permission)
    if (showOverlayDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDisclosureDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Floating Controls Disclosure",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column {
                    Text(
                        text = "ScrollSnap needs 'Display over other apps' permission strictly to show a floating draggable pill with 'Capture' and 'Save' buttons while you scroll through documents, chats, or web pages.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "• The overlay is hidden automatically during frame capture so it never blocks your content.\n• No screen data is logged or uploaded to the cloud.\n• All image stitching occurs 100% locally on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayDisclosureDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.testTag("confirm_overlay_disclosure")
                ) {
                    Text("Continue to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDisclosureDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CaptureTab(
    hasOverlay: Boolean,
    hasNotification: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onStartCapture: () -> Unit
) {
    val allPermissionsGranted = hasOverlay && hasNotification

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Hero Banner
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (allPermissionsGranted)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (allPermissionsGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (allPermissionsGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (allPermissionsGranted) "Ready to Capture" else "Permissions Required",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (allPermissionsGranted)
                                "Floating overlay is enabled. Use Quick Settings or tap Start below."
                            else
                                "Grant required permissions below to enable floating capture controls.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Primary Action Button
        item {
            Button(
                onClick = onStartCapture,
                enabled = allPermissionsGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("start_capture_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Screen Capture",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Permissions Checklist Card
        item {
            Text(
                text = "Required Setup",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        // Overlay Permission Row
        item {
            PermissionCard(
                title = "Display Over Other Apps",
                description = "Shows floating Capture and Save buttons over scrollable content.",
                isGranted = hasOverlay,
                onGrantClick = onRequestOverlay,
                testTag = "btn_grant_overlay"
            )
        }

        // Notification Permission Row (Android 13+)
        item {
            PermissionCard(
                title = "Notifications Permission",
                description = "Required to show the ongoing screen capture status and quick access to saved shots.",
                isGranted = hasNotification,
                onGrantClick = onRequestNotification,
                testTag = "btn_grant_notification"
            )
        }

        // Quick Settings Tile Integration Guide Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Quick Settings Tile",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Swipe down twice from the top of your screen to open Quick Settings.\n" +
                                "2. Tap the Pencil (Edit) icon.\n" +
                                "3. Drag 'Long Screenshot' into your active tiles.\n" +
                                "4. Now you can capture any screen anytime with a single tap!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit,
    testTag: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isGranted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Granted",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Button(
                    onClick = onGrantClick,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag(testTag)
                ) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
fun GalleryTab(
    screenshots: List<SavedScreenshotItem>,
    onRefresh: () -> Unit,
    onOpen: (SavedScreenshotItem) -> Unit,
    onShare: (SavedScreenshotItem) -> Unit,
    onDelete: (SavedScreenshotItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Saved Long Screenshots",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_gallery_button")) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh gallery")
                }
            }
        }

        if (screenshots.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Screenshots Yet",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Use the floating controls or Quick Settings tile to capture scrolling pages.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(screenshots, key = { it.id }) { item ->
                ScreenshotCard(
                    item = item,
                    onOpen = { onOpen(item) },
                    onShare = { onShare(item) },
                    onDelete = { onDelete(item) }
                )
            }
        }
    }
}

@Composable
fun ScreenshotCard(
    item: SavedScreenshotItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ScreenShare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.width} × ${item.height} px • ${formatFileSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatEpochDate(item.dateAddedSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun GuideAndPrivacyTab() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Privacy Guarantee
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy & Safety Guarantee",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "• 100% On-Device Processing: Frames are captured, analyzed, and stitched strictly inside your phone's memory and local app cache.\n" +
                                "• Zero Network Permissions: ScrollSnap does not have android.permission.INTERNET. No images, telemetry, or user data can ever be transmitted off your device.\n" +
                                "• Zero Accessibility APIs: Capture uses the official MediaProjection framework with explicit one-time consent.\n" +
                                "• Standard MediaStore: Saved images go directly to Pictures/Screenshots in your device gallery.",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // Step by Step Instructions
        item {
            Text(
                text = "How to Capture Long Screenshots",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        item {
            StepCard(
                step = "1",
                title = "Open Any App",
                description = "Navigate to the web page, conversation, receipt, or article you want to capture."
            )
        }

        item {
            StepCard(
                step = "2",
                title = "Tap Tile or Start Capture",
                description = "Tap 'Long Screenshot' from your Quick Settings drawer or launch it from ScrollSnap."
            )
        }

        item {
            StepCard(
                step = "3",
                title = "Scroll & Tap Capture (Down Arrow)",
                description = "Scroll down manually. Tap the floating Down Arrow button. Each tap grabs a frame, detects vertical overlap, and automatically trims headers and footers."
            )
        }

        item {
            StepCard(
                step = "4",
                title = "Tap Save (Checkmark)",
                description = "When you've captured everything, tap the Green Checkmark. The full page is stitched together and saved to your photo gallery."
            )
        }
    }
}

@Composable
fun StepCard(step: String, title: String, description: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = step,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.0f KB", kb)
    }
}

private fun formatEpochDate(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val date = Date(epochSec * 1000)
    return SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(date)
}
