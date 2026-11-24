package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.AppRoutingMode
import com.example.security.AppIntegrityGuard
import com.example.ui.components.AppPickerDialog
import com.example.ui.components.TelegramJoinDialog
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.TunnelScreen
import com.example.vpn.FloatingBubbleService
import kotlinx.coroutines.launch

private enum class NetRelayTab(
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
) {
    TUNNEL(R.string.tab_tunnel, Icons.Filled.VpnKey, Icons.Outlined.VpnKey, "nav_tunnel"),
    PROFILES(R.string.tab_profiles, Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks, "nav_profiles"),
    LOGS(R.string.tab_logs, Icons.Filled.Terminal, Icons.Outlined.Terminal, "nav_logs")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetRelayApp(
    viewModel: ProxyViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiScope = rememberCoroutineScope()

    if (!viewModel.integrityResult.isValid) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GppBad,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = stringResource(R.string.security_tamper_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = viewModel.integrityResult.reason
                            ?: stringResource(R.string.security_tamper_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val showTelegramDialog by viewModel.showTelegramDialog.collectAsStateWithLifecycle()
    val routingConfig by viewModel.routingConfig.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isAppsLoading by viewModel.isAppsLoading.collectAsStateWithLifecycle()
    val activeAppPickerMode by viewModel.activeAppPickerMode.collectAsStateWithLifecycle()
    val isBubbleRunning by viewModel.isBubbleRunning.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(NetRelayTab.TUNNEL) }
    var pendingBubbleEnable by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DisposableEffect(lifecycleOwner, pendingBubbleEnable) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingBubbleEnable) {
                pendingBubbleEnable = false
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
                    viewModel.syncDraftConfigForBubble()
                    FloatingBubbleService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnConsentResult(
            context = context,
            granted = result.resultCode == android.app.Activity.RESULT_OK
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed with VPN request regardless of notification preference
        viewModel.requestConnect(context) { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    val triggerConnect: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.requestConnect(context) { intent ->
                vpnPermissionLauncher.launch(intent)
            }
        }
    }

    val bubblePermissionMsg = stringResource(R.string.bubble_permission_needed)
    val onToggleBubble: (Boolean) -> Unit = { enable ->
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                pendingBubbleEnable = true
                uiScope.launch { snackbarHostState.showSnackbar(bubblePermissionMsg) }
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(overlayIntent) }
            } else {
                viewModel.syncDraftConfigForBubble()
                FloatingBubbleService.start(context)
            }
        } else {
            pendingBubbleEnable = false
            FloatingBubbleService.stop(context)
        }
    }

    BackHandler(enabled = currentTab != NetRelayTab.TUNNEL) {
        currentTab = NetRelayTab.TUNNEL
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NetRelayTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NetRelayTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxSize()
                    ) {
                        when (currentTab) {
                            NetRelayTab.TUNNEL -> TunnelScreen(
                                formState = formState,
                                tunnelState = tunnelState,
                                testState = testState,
                                routingConfig = routingConfig,
                                isBubbleRunning = isBubbleRunning,
                                onProtocolChange = viewModel::onProtocolChange,
                                onLabelChange = viewModel::onLabelChange,
                                onHostChange = viewModel::onHostChange,
                                onPortChange = viewModel::onPortChange,
                                onUsernameChange = viewModel::onUsernameChange,
                                onPasswordChange = viewModel::onPasswordChange,
                                onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                                onTestProxy = viewModel::testCurrentProxy,
                                onSaveProfile = viewModel::saveCurrentProfile,
                                onNewProfile = viewModel::clearFormForNew,
                                onConnectClick = triggerConnect,
                                onDisconnectClick = { viewModel.disconnect(context) },
                                onOpenAllowedApps = { viewModel.openAppPicker(AppRoutingMode.ALLOW_SELECTED) },
                                onOpenDisallowedApps = { viewModel.openAppPicker(AppRoutingMode.DISALLOW_SELECTED) },
                                onToggleBubble = onToggleBubble
                            )

                            NetRelayTab.PROFILES -> ProfilesScreen(
                                profiles = profiles,
                                tunnelState = tunnelState,
                                onSelectProfile = { profile ->
                                    viewModel.populateFromProfile(profile)
                                    currentTab = NetRelayTab.TUNNEL
                                },
                                onConnectProfile = { profile ->
                                    viewModel.connectWithProfile(context, profile) { intent ->
                                        vpnPermissionLauncher.launch(intent)
                                    }
                                    currentTab = NetRelayTab.TUNNEL
                                },
                                onDeleteProfile = viewModel::deleteProfile
                            )

                            NetRelayTab.LOGS -> DiagnosticsScreen(
                                logs = logs,
                                onClearLogs = viewModel::clearLogs
                            )
                        }
                    }
                }
            }
        }

        if (showTelegramDialog) {
            TelegramJoinDialog(
                onJoinClick = {
                    viewModel.dismissTelegramPrompt(dontShowAgain = false)
                    val tgIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(AppIntegrityGuard.telegramUrl())
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(tgIntent) }
                },
                onDontShowAgainClick = {
                    viewModel.dismissTelegramPrompt(dontShowAgain = true)
                },
                onDismissClick = {
                    viewModel.dismissTelegramPrompt(dontShowAgain = false)
                }
            )
        }

        val pickerMode = activeAppPickerMode
        if (pickerMode != null) {
            val selected = if (pickerMode == AppRoutingMode.ALLOW_SELECTED) {
                routingConfig.allowedApps
            } else {
                routingConfig.disallowedApps
            }
            AppPickerDialog(
                mode = pickerMode,
                installedApps = installedApps,
                isLoading = isAppsLoading,
                selectedPackages = selected,
                onTogglePackage = viewModel::toggleAppPackage,
                onClearSelection = viewModel::clearActiveAppSelection,
                onDismiss = viewModel::closeAppPicker
            )
        }
    }
}
