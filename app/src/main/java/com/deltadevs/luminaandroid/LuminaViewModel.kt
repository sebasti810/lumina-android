package com.deltadevs.luminaandroid

import android.app.Application
import android.system.Os
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.native.*
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

private fun Double.round(decimals: Int): Double {
    var multiplier = 1.0
    repeat(decimals) { multiplier *= 10 }
    return kotlin.math.round(this * multiplier) / multiplier
}

class LuminaViewModel(private val application: Application): AndroidViewModel(application) {
    private var node: LuminaNode? = null

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _nodeStatus = MutableLiveData("Not Started")
    val nodeStatus: LiveData<String> = _nodeStatus

    private val _networkType = MutableLiveData<Network?>(null)
    val networkType: LiveData<Network?> = _networkType

    private val _events = MutableLiveData<List<NodeEvent>>(emptyList())
    val events: LiveData<List<NodeEvent>> = _events

    private val _connectedPeers = MutableLiveData<ULong>(0u)
    val connectedPeers: LiveData<ULong> = _connectedPeers

    private val _trustedPeers = MutableLiveData<ULong>(0u)
    val trustedPeers: LiveData<ULong> = _trustedPeers

    private val _syncProgress = MutableLiveData(0.0)
    val syncProgress: LiveData<Double> = _syncProgress

    private val approxHeadersToSync = (30.0 * 24.0 * 60.0 * 60.0) / 12.0

    private var statsTimer: Timer? = null

    init {
        try {
            System.loadLibrary("native")
            val filesDir = application.filesDir.absolutePath
            Os.setenv("LUMINA_DATA_DIR", filesDir, true)

            initializeNode()
        } catch (e: Exception) {
            Log.e("LuminaViewModel", "Initialization error", e)
            _error.postValue("Initialization error: ${e.message}")
        }
    }

    private fun initializeNode() {
        try {
            val network = _networkType.value ?: Network.MOCHA
            Log.d("LuminaViewModel", "Initializing node with network: $network")
            node = LuminaNode(network)
            Log.d("LuminaViewModel", "Node initialized successfully")

            viewModelScope.launch {
                try {
                    val isRunning = node?.isRunning() ?: false
                    _isRunning.postValue(isRunning)
                    if (isRunning) {
                        startStatsUpdates()
                    }
                } catch (e: Exception) {
                    Log.e("LuminaViewModel", "Failed to check initial running state", e)
                }
            }
        } catch (e: Exception) {
            Log.e("LuminaViewModel", "Node initialization failed", e)
            _error.postValue(e.message)
        }
    }

    fun startNode() {
        viewModelScope.launch {
            try {
                _error.postValue(null)
                _nodeStatus.postValue("Starting...")

                val started = withContext(Dispatchers.IO) {
                    node?.start() ?: false
                }

                if (started) {
                    val isRunning = node?.isRunning() ?: false
                    _isRunning.postValue(isRunning)
                    _nodeStatus.postValue("Running")
                    startStatsUpdates()
                } else {
                    _error.postValue("Failed to start node")
                }
            } catch (e: Exception) {
                _error.postValue(e.message)
            }
        }
    }

    fun stopNode() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    node?.stop()
                }
                _isRunning.postValue(false)
                _nodeStatus.postValue("Stopped")
                stopStatsUpdates()
                _networkType.postValue(null)
                _connectedPeers.postValue(0u)
                _trustedPeers.postValue(0u)
                _syncProgress.postValue(0.0)
            } catch (e: Exception) {
                Log.e("LuminaViewModel", "Failed to stop node", e)
                _error.postValue(e.message)
            }
        }
    }

    private fun startStatsUpdates() {
        statsTimer?.cancel()
        statsTimer = Timer().apply {
            scheduleAtFixedRate(0, 2000) {
                updateStats()
            }
        }
    }

    private fun stopStatsUpdates() {
        statsTimer?.cancel()
        statsTimer = null
    }

    private fun updateStats() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val peerInfo = node?.peerTrackerInfo()
                    _connectedPeers.postValue(peerInfo?.numConnectedPeers)
                    _trustedPeers.postValue(peerInfo?.numConnectedTrustedPeers)

                    val syncInfo = node?.syncerInfo()
                    if (syncInfo != null) {
                        val syncWindowTail = syncInfo.subjectiveHead.toLong() - approxHeadersToSync.toLong()
                        var totalSyncedBlocks = 0.0

                        for (range in syncInfo.storedHeaders) {
                            val adjustedStart = maxOf(range.start.toLong(), syncWindowTail)
                            val adjustedEnd = maxOf(range.end.toLong(), syncWindowTail)
                            totalSyncedBlocks += adjustedEnd - adjustedStart
                        }

                        val progress = ((totalSyncedBlocks * 100.0) / approxHeadersToSync).round(1)
                        _syncProgress.postValue(minOf(progress, 100.0))
                    }

                    // Handle any events
                    var event = node?.eventsChannel()
                    while (event != null) {
                        handleEvent(event)
                        event = node?.eventsChannel()
                    }
                }
            } catch (e: Exception) {
                if (e !is LuminaException.NodeNotRunning) {
                    Log.e("LuminaViewModel", "Error updating stats", e)
                }
            }
        }
    }

    private fun handleEvent(event: NodeEvent) {
        val status = when (event) {
            is NodeEvent.ConnectingToBootnodes -> "Connecting to bootnodes..."
            is NodeEvent.PeerConnected ->
                "Connected to peer: ${event.id.peerId} (trusted: ${event.trusted})"
            is NodeEvent.PeerDisconnected ->
                "Peer disconnected: ${event.id.peerId}"
            is NodeEvent.SamplingStarted ->
                "Sampling data at height ${event.height}"
            is NodeEvent.SamplingFinished ->
                "Sampling finished at height ${event.height} (accepted: ${event.accepted})"
            is NodeEvent.FetchingHeadersStarted ->
                "Fetching headers ${event.fromHeight} to ${event.toHeight}"
            is NodeEvent.FetchingHeadersFinished ->
                "Headers synced ${event.fromHeight} to ${event.toHeight}"
            is NodeEvent.FetchingHeadersFailed ->
                "Sync failed: ${event.error}"
            else -> null
        }

        status?.let { _nodeStatus.postValue(it) }

        val currentEvents = _events.value.orEmpty().toMutableList()
        currentEvents.add(event)
        if (currentEvents.size > 100) {
            currentEvents.removeAt(0)
        }
        _events.postValue(currentEvents)
    }

    fun changeNetwork(network: Network) {
        viewModelScope.launch {
            try {
                if (node?.isRunning() == true) {
                    node?.stop()
                }

                _networkType.postValue(network)

                try {
                    node = LuminaNode(network)
                    startNode()
                } catch (e: Exception) {
                    _error.postValue("Failed to initialize new node: ${e.message}")
                    Log.e("LuminaViewModel", "Node initialization failed", e)
                }
            } catch (e: Exception) {
                _error.postValue("Network change failed: ${e.message}")
                Log.e("LuminaViewModel", "Network change failed", e)
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch {
            stopNode()
        }
        super.onCleared()
    }
}