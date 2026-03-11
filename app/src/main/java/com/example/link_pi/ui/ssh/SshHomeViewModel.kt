package com.example.link_pi.ui.ssh

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.link_pi.agent.SshManager
import com.example.link_pi.data.Credential
import com.example.link_pi.data.CredentialStorage
import com.example.link_pi.data.SavedServer
import com.example.link_pi.data.SavedServerStorage
import com.example.link_pi.network.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SshHomeViewModel(application: Application) : AndroidViewModel(application) {

    val sshManager = SshManager.getInstance(application)
    val serverStorage = SavedServerStorage(application)
    val credentialStorage = CredentialStorage(application)
    private val aiConfig = AiConfig(application)

    // ── Active Sessions ──
    private val _activeSessions = MutableStateFlow<List<SshManager.SshSessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<SshManager.SshSessionInfo>> = _activeSessions.asStateFlow()

    // ── Saved Servers ──
    private val _savedServers = MutableStateFlow<List<SavedServer>>(emptyList())
    val savedServers: StateFlow<List<SavedServer>> = _savedServers.asStateFlow()

    // ── Credentials ──
    private val _credentials = MutableStateFlow<List<Credential>>(emptyList())
    val credentials: StateFlow<List<Credential>> = _credentials.asStateFlow()

    // ── Connection state ──
    private val _connectingId = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connectingId.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    // ── Result: session ID of newly connected session ──
    private val _connectedSessionId = MutableStateFlow<String?>(null)
    val connectedSessionId: StateFlow<String?> = _connectedSessionId.asStateFlow()

    // ── Whether to fallback to manual mode ──
    private val _fallbackManual = MutableStateFlow(false)
    val fallbackManual: StateFlow<Boolean> = _fallbackManual.asStateFlow()

    fun refresh() {
        _activeSessions.value = sshManager.getActiveSessions()
        _savedServers.value = serverStorage.loadAll()
        _credentials.value = credentialStorage.loadAll()
    }

    fun deleteServer(id: String) {
        serverStorage.delete(id)
        _savedServers.value = serverStorage.loadAll()
    }

    fun saveServer(server: SavedServer) {
        serverStorage.save(server)
        _savedServers.value = serverStorage.loadAll()
    }

    fun findServer(id: String): SavedServer? = serverStorage.findById(id)

    fun saveCredential(credential: Credential) {
        credentialStorage.save(credential)
        _credentials.value = credentialStorage.loadAll()
    }

    fun deleteCredential(id: String) {
        credentialStorage.delete(id)
        _credentials.value = credentialStorage.loadAll()
    }

    fun clearConnectResult() {
        _connectedSessionId.value = null
        _fallbackManual.value = false
        _connectError.value = null
        _connectingId.value = null
    }

    /** Check if AI mode should be skipped (no model or no internet). */
    private fun shouldUseManualMode(): Boolean {
        val model = aiConfig.activeModel
        if (model.id.isBlank() || model.apiKey.isBlank()) return true
        // No internet → fallback to manual (local network SSH still works)
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Connect to a saved server. */
    fun connectToServer(server: SavedServer) {
        if (_connectingId.value != null) return
        _connectingId.value = server.id
        _connectError.value = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cred = if (server.credentialId.isNotBlank()) {
                        credentialStorage.findById(server.credentialId)
                    } else null

                    sshManager.connect(
                        host = server.host,
                        port = server.port,
                        username = cred?.username ?: "",
                        password = cred?.secret,
                        credentialName = if (cred == null && server.credentialName.isNotBlank())
                            server.credentialName else null
                    )
                }

                if (result.startsWith("Error:")) {
                    _connectError.value = result.removePrefix("Error: ")
                } else {
                    val json = JSONObject(result)
                    val sid = json.getString("session_id")
                    _connectedSessionId.value = sid
                    _fallbackManual.value = shouldUseManualMode()
                }
            } catch (e: Exception) {
                _connectError.value = e.message ?: "连接失败"
            } finally {
                _connectingId.value = null
                _activeSessions.value = sshManager.getActiveSessions()
            }
        }
    }

    /** Quick connect with host and credential. */
    fun quickConnect(host: String, port: Int, credentialId: String) {
        if (_connectingId.value != null) return
        _connectingId.value = "quick"
        _connectError.value = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cred = if (credentialId.isNotBlank()) {
                        credentialStorage.findById(credentialId)
                    } else null

                    sshManager.connect(
                        host = host,
                        port = port,
                        username = cred?.username ?: "",
                        password = cred?.secret
                    )
                }

                if (result.startsWith("Error:")) {
                    _connectError.value = result.removePrefix("Error: ")
                } else {
                    val json = JSONObject(result)
                    val sid = json.getString("session_id")
                    _connectedSessionId.value = sid
                    _fallbackManual.value = shouldUseManualMode()
                }
            } catch (e: Exception) {
                _connectError.value = e.message ?: "连接失败"
                _fallbackManual.value = true
            } finally {
                _connectingId.value = null
                _activeSessions.value = sshManager.getActiveSessions()
            }
        }
    }

    /** Re-enter an existing active session. */
    fun reenterSession(sessionId: String) {
        _connectedSessionId.value = sessionId
        _fallbackManual.value = shouldUseManualMode()
    }
}
