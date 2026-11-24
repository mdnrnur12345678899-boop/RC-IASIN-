package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ProxyProtocol
import com.example.ui.ProxyFormState
import com.example.ui.ProxyTestUiState
import com.example.ui.components.HeroTunnelCard
import com.example.vpn.TunnelRuntimeState
import com.example.vpn.TunnelStatus

@Composable
fun TunnelScreen(
    formState: ProxyFormState,
    tunnelState: TunnelRuntimeState,
    testState: ProxyTestUiState,
    onProtocolChange: (ProxyProtocol) -> Unit,
    onLabelChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onTestProxy: () -> Unit,
    onSaveProfile: () -> Unit,
    onNewProfile: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLocked = tunnelState.status == TunnelStatus.CONNECTED ||
        tunnelState.status == TunnelStatus.CONNECTING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeroTunnelCard(
            tunnelState = tunnelState,
            onConnectClick = onConnectClick,
            onDisconnectClick = onDisconnectClick
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RC PROXY Profile",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = onNewProfile,
                        enabled = !isLocked,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("new_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.action_new_profile))
                    }
                }

                // Protocol Segmented Selector
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ProxyProtocol.entries.forEachIndexed { index, protocol ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ProxyProtocol.entries.size
                            ),
                            selected = formState.protocol == protocol,
                            enabled = !isLocked,
                            onClick = { onProtocolChange(protocol) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("protocol_${protocol.name.lowercase()}")
                        ) {
                            Text(
                                text = protocol.displayName,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                // Server Host & Port Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = formState.host,
                        onValueChange = onHostChange,
                        enabled = !isLocked,
                        singleLine = true,
                        isError = formState.hostError != null,
                        supportingText = formState.hostError?.let { err -> { Text(err) } },
                        label = { Text(stringResource(R.string.label_server_host)) },
                        placeholder = { Text(stringResource(R.string.hint_server_host)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(0.66f)
                            .testTag("server_host_input")
                    )

                    OutlinedTextField(
                        value = formState.portText,
                        onValueChange = onPortChange,
                        enabled = !isLocked,
                        singleLine = true,
                        isError = formState.portError != null,
                        supportingText = formState.portError?.let { err -> { Text(err) } },
                        label = { Text(stringResource(R.string.label_port)) },
                        placeholder = { Text(stringResource(R.string.hint_port)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(0.34f)
                            .testTag("server_port_input")
                    )
                }

                // Username
                OutlinedTextField(
                    value = formState.username,
                    onValueChange = onUsernameChange,
                    enabled = !isLocked,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_username)) },
                    placeholder = { Text(stringResource(R.string.hint_username)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("username_input")
                )

                // Password
                OutlinedTextField(
                    value = formState.password,
                    onValueChange = onPasswordChange,
                    enabled = !isLocked,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_password)) },
                    placeholder = { Text(stringResource(R.string.hint_password)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = onTogglePasswordVisibility,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_password_visibility")
                        ) {
                            Icon(
                                imageVector = if (formState.isPasswordVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = stringResource(R.string.cd_toggle_password)
                            )
                        }
                    },
                    visualTransformation = if (formState.isPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input")
                )

                // Optional Profile Label
                OutlinedTextField(
                    value = formState.label,
                    onValueChange = onLabelChange,
                    enabled = !isLocked,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_profile_name)) },
                    placeholder = { Text(stringResource(R.string.hint_profile_name)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Label,
                            contentDescription = null
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_label_input")
                )

                // Inline Proxy Verification Banner
                AnimatedVisibility(
                    visible = testState !is ProxyTestUiState.Idle,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ProxyVerificationBanner(testState = testState)
                }

                // Verify & Save Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestProxy,
                        enabled = testState !is ProxyTestUiState.Testing,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("verify_proxy_button")
                    ) {
                        if (testState is ProxyTestUiState.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_test_proxy))
                        }
                    }

                    FilledTonalButton(
                        onClick = onSaveProfile,
                        enabled = !isLocked,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_save_profile))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ProxyVerificationBanner(testState: ProxyTestUiState) {
    when (testState) {
        is ProxyTestUiState.Testing -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Authenticating & checking exit IP…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        is ProxyTestUiState.Success -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Proxy Verified • Exit IP: ${testState.result.exitIp}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Latency ${testState.result.latencyMs}ms (Handshake ${testState.result.handshakeMs}ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        is ProxyTestUiState.Failure -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = testState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        ProxyTestUiState.Idle -> Unit
    }
}
