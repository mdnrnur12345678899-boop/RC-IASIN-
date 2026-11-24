package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.TunnelScreen

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
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(NetRelayTab.TUNNEL) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
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
                                onDisconnectClick = { viewModel.disconnect(context) }
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
    }
}
