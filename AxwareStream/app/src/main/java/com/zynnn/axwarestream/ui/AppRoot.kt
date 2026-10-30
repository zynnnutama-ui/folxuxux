package com.zynnn.axwarestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.MainActions
import com.zynnn.axwarestream.service.StreamForegroundService
import com.zynnn.axwarestream.ui.screens.*
import com.zynnn.axwarestream.ui.theme.Ax

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Rounded.Sensors),
    STUDIO("Overlay", Icons.Rounded.Layers),
    INTERACTION("Interaksi", Icons.Rounded.Bolt),
    SETTINGS("Setelan", Icons.Rounded.Tune),
}

@Composable
fun AppRoot(service: StreamForegroundService?, actions: MainActions) {
    val ctx = LocalContext.current
    val app = remember { AxwareApp.from(ctx) }
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }

    Scaffold(
        containerColor = Ax.Bg,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Surface(color = Color.Transparent, tonalElevation = 0.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(CircleShape)
                        .background(Ax.Surface2)
                        .border(1.dp, Ax.Line.copy(alpha = 0.55f), CircleShape)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Tab.entries.forEach { t ->
                            val sel = t == tab
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { tab = t }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (sel) Ax.AccentSoft else Color.Transparent)
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        t.icon,
                                        contentDescription = t.label,
                                        tint = if (sel) Ax.Accent else Ax.Muted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    t.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) Ax.Text else Ax.Muted
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Ax.Bg)) {
            when (tab) {
                Tab.LIVE        -> LiveScreen(app, service, actions)
                Tab.STUDIO      -> StudioScreen(app)
                Tab.INTERACTION -> InteractionScreen(app)
                Tab.SETTINGS    -> SettingsScreen(app, service, actions)
            }
        }
    }
}
