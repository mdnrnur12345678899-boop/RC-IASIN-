package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.vpn.TunnelRuntimeState
import com.example.vpn.TunnelStatus
import kotlinx.coroutines.delay

@Composable
fun HeroTunnelCard(
    tunnelState: TunnelRuntimeState,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = tunnelState.status == TunnelStatus.CONNECTED
    val isConnecting = tunnelState.status == TunnelStatus.CONNECTING
    val isError = tunnelState.status == TunnelStatus.ERROR

    val accentColor by animateColorAsState(
        targetValue = when (tunnelState.status) {
            TunnelStatus.CONNECTED -> MaterialTheme.colorScheme.primary
            TunnelStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
            TunnelStatus.ERROR -> MaterialTheme.colorScheme.error
            TunnelStatus.DISCONNECTED -> MaterialTheme.colorScheme.secondary
        },
        label = "hero_accent"
    )

    var uptimeSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tunnelState.status, tunnelState.connectedSinceMs) {
        val startMs = tunnelState.connectedSinceMs
        if (tunnelState.status == TunnelStatus.CONNECTED && startMs != null) {
            while (true) {
                uptimeSeconds = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        } else {
            uptimeSeconds = 0L
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_radius"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF0E1627),
        border = BorderStroke(1.dp, accentColor.copy(alpha = if (isConnected) 0.5f else 0.25f)),
        tonalElevation = 4.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_relay_mesh_1795535639435),
                contentDescription = stringResource(R.string.cd_hero_mesh),
                modifier = Modifier
                    .matchParentSize()
                    .alpha(if (isConnected) 0.36f else 0.2f),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC0B101B),
                                Color(0xE60E1627)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.size(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isConnected || isConnecting) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(
                                        color = accentColor.copy(alpha = pulseAlpha),
                                        radius = (size.minDimension / 2f) * pulseRadius,
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (tunnelState.status) {
                                        TunnelStatus.CONNECTED -> Icons.Rounded.Shield
                                        TunnelStatus.CONNECTING -> Icons.Rounded.Sync
                                        TunnelStatus.ERROR -> Icons.Rounded.ErrorOutline
                                        TunnelStatus.DISCONNECTED -> Icons.Rounded.Lock
                                    },
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when (tunnelState.status) {
                                    TunnelStatus.DISCONNECTED -> stringResource(R.string.status_disconnected)
                                    TunnelStatus.CONNECTING -> stringResource(R.string.status_connecting)
                                    TunnelStatus.CONNECTED -> stringResource(R.string.status_connected)
                                    TunnelStatus.ERROR -> stringResource(R.string.status_error)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFFF8FAFC)
                            )
                            val subtitle = when {
                                isError && !tunnelState.errorMessage.isNullOrBlank() -> tunnelState.errorMessage
                                tunnelState.activeConfig != null -> {
                                    val cfg = tunnelState.activeConfig
                                    "${cfg.protocol.displayName} • ${cfg.host}:${cfg.port}"
                                }
                                else -> "SOCKS5 & HTTP Tunnel Ready"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isError) Color(0xFFFF8CA0) else Color(0xFF94A3B8),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Live Telemetry Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniTile(
                        label = stringResource(R.string.metric_download),
                        value = if (isConnected) FormatUtils.formatSpeed(tunnelState.downloadSpeedBps) else "0 B/s",
                        subValue = FormatUtils.formatBytes(tunnelState.bytesDownloaded),
                        icon = Icons.Rounded.ArrowDownward,
                        tint = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMiniTile(
                        label = stringResource(R.string.metric_upload),
                        value = if (isConnected) FormatUtils.formatSpeed(tunnelState.uploadSpeedBps) else "0 B/s",
                        subValue = FormatUtils.formatBytes(tunnelState.bytesUploaded),
                        icon = Icons.Rounded.ArrowUpward,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniTile(
                        label = stringResource(R.string.metric_exit_ip),
                        value = tunnelState.exitIp ?: "—",
                        subValue = tunnelState.latencyMs?.let { "${it} ms" } ?: "${tunnelState.activeSockets} sockets",
                        icon = Icons.Rounded.Public,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMiniTile(
                        label = stringResource(R.string.metric_duration),
                        value = FormatUtils.formatDuration(uptimeSeconds),
                        subValue = "${tunnelState.activeSockets} active",
                        icon = Icons.Rounded.Shield,
                        tint = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        if (isConnected || isConnecting) onDisconnectClick() else onConnectClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("connect_toggle_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected || isConnecting) {
                            Color(0xFFFF5C77)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isConnected || isConnecting) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.action_disconnect),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConnected) {
                                stringResource(R.string.action_disconnect)
                            } else {
                                stringResource(R.string.action_connect)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniTile(
    label: String,
    value: String,
    subValue: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF141E33).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color(0xFF23304D))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFF1F5F9),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
