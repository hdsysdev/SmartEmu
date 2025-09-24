package com.hddev.smartemu.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.SimulationStatus

/**
 * Compose UI component for simulation control and status display.
 * Provides start/stop buttons, NFC status indicators, and permission handling.
 */
@Composable
fun SimulationControlPanel(
    simulationStatus: SimulationStatus,
    nfcAvailable: Boolean,
    hasNfcPermission: Boolean,
    canStartSimulation: Boolean,
    canStopSimulation: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onStartSimulation: () -> Unit,
    onStopSimulation: () -> Unit,
    onRequestPermissions: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "Simulation Control",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            
            // Status Indicators
            StatusIndicatorsSection(
                nfcAvailable = nfcAvailable,
                hasNfcPermission = hasNfcPermission,
                simulationStatus = simulationStatus
            )
            
            // Control Buttons
            ControlButtonsSection(
                simulationStatus = simulationStatus,
                canStartSimulation = canStartSimulation,
                canStopSimulation = canStopSimulation,
                isLoading = isLoading,
                onStartSimulation = onStartSimulation,
                onStopSimulation = onStopSimulation
            )
            
            // Permission Request Section
            if (nfcAvailable && !hasNfcPermission) {
                PermissionRequestSection(
                    onRequestPermissions = onRequestPermissions,
                    isLoading = isLoading
                )
            }
            
            // Error Display
            errorMessage?.let { error ->
                ErrorDisplaySection(
                    errorMessage = error,
                    onClearError = onClearError
                )
            }
        }
    }
}

@Composable
private fun StatusIndicatorsSection(
    nfcAvailable: Boolean,
    hasNfcPermission: Boolean,
    simulationStatus: SimulationStatus
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "System Status",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        
        // NFC Availability Status
        StatusIndicator(
            label = "NFC Hardware",
            isActive = nfcAvailable,
            activeText = "Available",
            inactiveText = "Not Available",
            activeIcon = Icons.Filled.Nfc,
            inactiveIcon = Icons.Filled.NfcOff
        )
        
        // NFC Permission Status
        StatusIndicator(
            label = "NFC Permissions",
            isActive = hasNfcPermission,
            activeText = "Granted",
            inactiveText = "Required",
            activeIcon = Icons.Filled.CheckCircle,
            inactiveIcon = Icons.Filled.Warning
        )
        
        // Simulation Status
        SimulationStatusIndicator(
            status = simulationStatus
        )
    }
}

@Composable
private fun StatusIndicator(
    label: String,
    isActive: Boolean,
    activeText: String,
    inactiveText: String,
    activeIcon: ImageVector,
    inactiveIcon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isActive) activeIcon else inactiveIcon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SimulationStatusIndicator(
    status: SimulationStatus
) {
    val (icon, color, isAnimated) = when (status) {
        SimulationStatus.STOPPED -> Triple(Icons.Filled.Stop, MaterialTheme.colorScheme.outline, false)
        SimulationStatus.STARTING -> Triple(Icons.Filled.PlayArrow, MaterialTheme.colorScheme.primary, true)
        SimulationStatus.ACTIVE -> Triple(Icons.Filled.PlayCircle, MaterialTheme.colorScheme.primary, false)
        SimulationStatus.STOPPING -> Triple(Icons.Filled.Stop, MaterialTheme.colorScheme.outline, true)
        SimulationStatus.ERROR -> Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, false)
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Simulation",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isAnimated) {
                AnimatedStatusIcon(
                    icon = icon,
                    color = color
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = status.getDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
private fun AnimatedStatusIcon(
    icon: ImageVector,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_animation")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_animation"
    )
    
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier
            .size(16.dp)
            .alpha(alpha)
    )
}

@Composable
private fun ControlButtonsSection(
    simulationStatus: SimulationStatus,
    canStartSimulation: Boolean,
    canStopSimulation: Boolean,
    isLoading: Boolean,
    onStartSimulation: () -> Unit,
    onStopSimulation: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Controls",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Start Button
            Button(
                onClick = onStartSimulation,
                enabled = canStartSimulation && !isLoading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isLoading && simulationStatus == SimulationStatus.STARTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start")
            }
            
            // Stop Button
            Button(
                onClick = onStopSimulation,
                enabled = canStopSimulation && !isLoading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isLoading && simulationStatus == SimulationStatus.STOPPING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
}

@Composable
private fun PermissionRequestSection(
    onRequestPermissions: () -> Unit,
    isLoading: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "NFC Permissions Required",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Text(
                text = "This app needs NFC permissions to simulate passport chips. Please grant permissions to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Button(
                onClick = onRequestPermissions,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
private fun ErrorDisplaySection(
    errorMessage: String,
    onClearError: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                IconButton(
                    onClick = onClearError,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear error",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}