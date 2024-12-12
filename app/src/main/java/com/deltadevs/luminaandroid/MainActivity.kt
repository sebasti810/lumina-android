package com.deltadevs.luminaandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.livedata.observeAsState
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import uniffi.native.Network
import uniffi.native.NodeEvent

fun Network.displayName(): String = when (this) {
    Network.Mainnet -> "Mainnet"
    Network.Arabica -> "Arabica"
    Network.Mocha -> "Mocha"
    is Network.Custom -> "Custom: ${this.id}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        val viewModel by viewModels<LuminaViewModel> {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LuminaViewModel(application) as T
                }
            }
        }

        setContent {
            MaterialTheme {
                LuminaApp(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuminaApp(viewModel: LuminaViewModel) {
    var showNetworkDialog by remember { mutableStateOf(false) }

    val error by viewModel.error.observeAsState()
    val isRunning by viewModel.isRunning.observeAsState()
    val nodeStatus by viewModel.nodeStatus.observeAsState()
    val events by viewModel.events.observeAsState()
    val connectedPeers by viewModel.connectedPeers.observeAsState()
    val trustedPeers by viewModel.trustedPeers.observeAsState()
    val syncProgress by viewModel.syncProgress.observeAsState()
    val networkType by viewModel.networkType.observeAsState()

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    TopAppBar(
                        title = { Text("Lumina Node") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    networkType?.let { network ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Network: ${networkType?.displayName() ?: "Unknown"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            error?.let { errorMessage ->
                if (!errorMessage.isNullOrBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorMessage,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (isRunning != true) {
                Button(onClick = { showNetworkDialog = true }) {
                    Text("Start Node")
                }
            } else {
                StatusCard(
                    nodeStatus = nodeStatus ?: "Unknown",
                    isRunning = isRunning ?: false,
                    connectedPeers = connectedPeers ?: 0u,
                    trustedPeers = trustedPeers ?: 0u,
                    syncProgress = syncProgress ?: 0.0
                )

                EventsList(events = events ?: emptyList())

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.stopNode()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop")
                    }

                    Button(onClick = {
                        viewModel.restartNode()
                    }) {
                        Text("Restart")
                    }
                }
            }
        }
    }

    if (showNetworkDialog) {
        NetworkSelectionDialog(
            onDismiss = { showNetworkDialog = false },
            onNetworkSelected = { network ->
                viewModel.changeNetwork(network)
                showNetworkDialog = false
            }
        )
    }
}

@Composable
fun StatusCard(
    nodeStatus: String,
    isRunning: Boolean,
    connectedPeers: ULong,
    trustedPeers: ULong,
    syncProgress: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Node Status")
                Text(
                    text = if (isRunning) "Running" else "Stopped",
                    color = if (isRunning)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            if (isRunning) {
                Divider()

                LinearProgressIndicator(
                    progress = (syncProgress / 100f).toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sync Progress")
                    Text(String.format("%.1f%%", syncProgress))
                }

                Divider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Connected Peers")
                    Text("$connectedPeers")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Trusted Peers")
                    Text("$trustedPeers")
                }
            }
        }
    }
}

@Composable
fun EventsList(events: List<NodeEvent>) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Node Events",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
            ) {
                items(events) { event ->
                    Text(
                        text = formatEvent(event),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            LaunchedEffect(events.size) {
                if (events.isNotEmpty()) {
                    listState.animateScrollToItem(events.size - 1)
                }
            }
        }
    }
}

private fun formatEvent(event: NodeEvent): String = when (event) {
    is NodeEvent.ConnectingToBootnodes -> "🔄 Connecting to bootnodes..."
    is NodeEvent.PeerConnected ->
        "✅ Connected to peer: ${event.id.peerId} (trusted: ${event.trusted})"
    is NodeEvent.PeerDisconnected ->
        "❌ Disconnected from peer: ${event.id.peerId}"
    is NodeEvent.SamplingStarted ->
        "📊 Sampling at height ${event.height}"
    is NodeEvent.SamplingFinished ->
        "✓ Sampling finished at ${event.height} (accepted: ${event.accepted})"
    is NodeEvent.FetchingHeadersStarted ->
        "📥 Fetching headers ${event.fromHeight} → ${event.toHeight}"
    is NodeEvent.FetchingHeadersFinished ->
        "✓ Headers synced ${event.fromHeight} → ${event.toHeight}"
    is NodeEvent.FetchingHeadersFailed ->
        "❌ Sync failed: ${event.error}"
    else -> event.toString()
}

@Composable
fun NetworkSelectionDialog(
    onDismiss: () -> Unit,
    onNetworkSelected: (Network) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Network") },
        text = {
            Column {
                listOf(
                    Network.Mainnet,
                    Network.Arabica,
                    Network.Mocha,
                    Network.Custom("private")
                ).forEach { network ->
                    TextButton(
                        onClick = { onNetworkSelected(network) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(network.displayName())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}