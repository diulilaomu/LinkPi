package com.example.link_pi.agent

import android.content.Context
import com.example.link_pi.data.CredentialStorage
import com.jcraft.jsch.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages SSH sessions and operations for AI tool calls.
 *
 * Supports: password auth, key-based auth, command execution,
 * SFTP upload/download, port forwarding.
 *
 * Encryption algorithms supported by JSch (mwiede fork):
 * - Key Exchange: curve25519-sha256, ecdh-sha2-nistp256/384/521, diffie-hellman-group16/18-sha512, diffie-hellman-group14-sha256, diffie-hellman-group-exchange-sha256
 * - Host Key: ssh-ed25519, ecdsa-sha2-nistp256/384/521, rsa-sha2-512/256, ssh-rsa
 * - Cipher: chacha20-poly1305@openssh.com, aes128/192/256-gcm@openssh.com, aes128/192/256-ctr, aes128/192/256-cbc
 * - MAC: hmac-sha2-256/512, hmac-sha2-256/512-etm@openssh.com, hmac-sha1
 */
class SshManager(context: Context) {

    private val credentialStorage = CredentialStorage(context)
    private val jsch = JSch()
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    private data class SessionEntry(
        val session: Session,
        val host: String,
        val port: Int,
        val username: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** Connect to an SSH server. Returns a session ID. */
    fun connect(
        host: String,
        port: Int = 22,
        username: String,
        password: String? = null,
        privateKey: String? = null,
        credentialName: String? = null
    ): String {
        // Resolve credential if specified
        var resolvedUser = username
        var resolvedPassword = password
        var resolvedKey = privateKey

        if (credentialName != null) {
            val cred = credentialStorage.findByName(credentialName)
                ?: return "Error: 凭据 '$credentialName' 未找到。请在设置→凭据管理中添加。"
            if (resolvedUser.isBlank() && cred.username.isNotBlank()) resolvedUser = cred.username
            if (resolvedPassword == null && cred.secret.isNotBlank()) resolvedPassword = cred.secret
        }

        if (resolvedUser.isBlank()) return "Error: 未提供用户名"
        if (resolvedPassword == null && resolvedKey == null) {
            return "Error: 需要提供 password、private_key 或 credential_name"
        }

        // Clean up existing sessions to the same host:port:user
        val existingId = sessions.entries.find {
            it.value.host == host && it.value.port == port && it.value.username == resolvedUser
        }?.key
        if (existingId != null) {
            try { sessions.remove(existingId)?.session?.disconnect() } catch (_: Exception) {}
        }

        return try {
            // Set up private key if provided
            if (resolvedKey != null) {
                val keyBytes = resolvedKey.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("key_${System.currentTimeMillis()}", keyBytes, null, null)
            }

            val session = jsch.getSession(resolvedUser, host, port)

            // Configure algorithms — prefer modern, keep broad compatibility
            val config = Properties().apply {
                // Disable strict host key checking for AI tool usage
                setProperty("StrictHostKeyChecking", "no")
                // Preferred key exchange algorithms
                setProperty(
                    "kex",
                    "curve25519-sha256,curve25519-sha256@libssh.org," +
                    "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
                    "diffie-hellman-group16-sha512,diffie-hellman-group18-sha512," +
                    "diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256," +
                    "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1"
                )
                // Preferred host key algorithms
                setProperty(
                    "server_host_key",
                    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
                    "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
                )
                // Preferred ciphers
                setProperty(
                    "cipher.s2c",
                    "chacha20-poly1305@openssh.com," +
                    "aes128-gcm@openssh.com,aes256-gcm@openssh.com," +
                    "aes128-ctr,aes192-ctr,aes256-ctr," +
                    "aes128-cbc,aes192-cbc,aes256-cbc"
                )
                setProperty(
                    "cipher.c2s",
                    "chacha20-poly1305@openssh.com," +
                    "aes128-gcm@openssh.com,aes256-gcm@openssh.com," +
                    "aes128-ctr,aes192-ctr,aes256-ctr," +
                    "aes128-cbc,aes192-cbc,aes256-cbc"
                )
                // Preferred MACs
                setProperty(
                    "mac.s2c",
                    "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com," +
                    "hmac-sha2-256,hmac-sha2-512,hmac-sha1"
                )
                setProperty(
                    "mac.c2s",
                    "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com," +
                    "hmac-sha2-256,hmac-sha2-512,hmac-sha1"
                )
            }
            session.setConfig(config)

            if (resolvedPassword != null) {
                session.setPassword(resolvedPassword)
            }

            session.timeout = 15_000 // 15s connect timeout
            session.connect()

            val sessionId = "ssh_${System.currentTimeMillis().toString(36)}"
            sessions[sessionId] = SessionEntry(session, host, port, resolvedUser)

            JSONObject().apply {
                put("session_id", sessionId)
                put("host", host)
                put("port", port)
                put("username", resolvedUser)
                put("status", "connected")
                put("server_version", session.serverVersion ?: "unknown")
            }.toString(2)
        } catch (e: JSchException) {
            "Error: SSH连接失败: ${e.message}"
        }
    }

    /** Execute a command on an SSH session. Returns stdout/stderr. */
    fun exec(sessionId: String, command: String, timeoutMs: Int = 30_000): String {
        val entry = sessions[sessionId]
            ?: return "Error: 会话 '$sessionId' 不存在或已断开。请使用 ssh_connect 建立连接。"
        if (!entry.session.isConnected) {
            sessions.remove(sessionId)
            return "Error: 会话已断开，请重新连接。"
        }

        return try {
            val channel = entry.session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.inputStream = null

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.outputStream = stdout
            channel.setErrStream(stderr)

            channel.connect(timeoutMs)

            // Wait for command to finish
            val startTime = System.currentTimeMillis()
            while (!channel.isClosed) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    channel.disconnect()
                    return "Error: 命令执行超时 (${timeoutMs / 1000}s)"
                }
                Thread.sleep(100)
            }

            val exitCode = channel.exitStatus
            channel.disconnect()

            val out = stdout.toString("UTF-8")
            val err = stderr.toString("UTF-8")

            // Limit output size
            val maxLen = 8000
            val truncatedOut = if (out.length > maxLen) out.take(maxLen) + "\n...(输出已截断)" else out
            val truncatedErr = if (err.length > maxLen) err.take(maxLen) + "\n...(错误输出已截断)" else err

            JSONObject().apply {
                put("exit_code", exitCode)
                put("stdout", truncatedOut)
                if (err.isNotBlank()) put("stderr", truncatedErr)
            }.toString(2)
        } catch (e: Exception) {
            "Error: 命令执行失败: ${e.message}"
        }
    }

    /** Upload a file from workspace to remote server via SFTP. */
    fun upload(sessionId: String, localContent: String, remotePath: String): String {
        val entry = sessions[sessionId]
            ?: return "Error: 会话 '$sessionId' 不存在或已断开。"
        if (!entry.session.isConnected) {
            sessions.remove(sessionId)
            return "Error: 会话已断开，请重新连接。"
        }

        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)

            // Create parent directories if needed
            val parentDir = remotePath.substringBeforeLast('/', "")
            if (parentDir.isNotBlank()) {
                mkdirRecursive(channel, parentDir)
            }

            // Upload content as bytes
            val bytes = localContent.toByteArray(Charsets.UTF_8)
            channel.put(bytes.inputStream(), remotePath)
            channel.disconnect()

            "文件已上传到 $remotePath (${bytes.size} bytes)"
        } catch (e: Exception) {
            "Error: SFTP上传失败: ${e.message}"
        }
    }

    /** Download a file from remote server. Returns file content. */
    fun download(sessionId: String, remotePath: String): String {
        val entry = sessions[sessionId]
            ?: return "Error: 会话 '$sessionId' 不存在或已断开。"
        if (!entry.session.isConnected) {
            sessions.remove(sessionId)
            return "Error: 会话已断开，请重新连接。"
        }

        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)

            val output = ByteArrayOutputStream()
            channel.get(remotePath, output)
            channel.disconnect()

            val content = output.toString("UTF-8")
            val maxLen = 8000
            if (content.length > maxLen) {
                content.take(maxLen) + "\n...(内容已截断，共 ${content.length} 字符)"
            } else {
                content
            }
        } catch (e: Exception) {
            "Error: SFTP下载失败: ${e.message}"
        }
    }

    /** List files in a remote directory via SFTP. */
    fun listRemote(sessionId: String, remotePath: String = "."): String {
        val entry = sessions[sessionId]
            ?: return "Error: 会话 '$sessionId' 不存在或已断开。"
        if (!entry.session.isConnected) {
            sessions.remove(sessionId)
            return "Error: 会话已断开，请重新连接。"
        }

        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)

            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(remotePath) as java.util.Vector<ChannelSftp.LsEntry>
            channel.disconnect()

            val arr = JSONArray()
            for (e in entries) {
                if (e.filename == "." || e.filename == "..") continue
                arr.put(JSONObject().apply {
                    put("name", e.filename)
                    put("size", e.attrs.size)
                    put("is_dir", e.attrs.isDir)
                    put("permissions", e.attrs.permissionsString)
                    put("mtime", e.attrs.mTime.toLong())
                })
            }
            arr.toString(2)
        } catch (e: Exception) {
            "Error: SFTP列表失败: ${e.message}"
        }
    }

    /** Set up local port forwarding. Returns the assigned local port. */
    fun portForward(
        sessionId: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int
    ): String {
        val entry = sessions[sessionId]
            ?: return "Error: 会话 '$sessionId' 不存在或已断开。"
        if (!entry.session.isConnected) {
            sessions.remove(sessionId)
            return "Error: 会话已断开，请重新连接。"
        }

        return try {
            val assigned = entry.session.setPortForwardingL(localPort, remoteHost, remotePort)
            JSONObject().apply {
                put("local_port", assigned)
                put("remote_host", remoteHost)
                put("remote_port", remotePort)
                put("status", "forwarding")
            }.toString(2)
        } catch (e: Exception) {
            "Error: 端口转发失败: ${e.message}"
        }
    }

    /** Disconnect an SSH session. */
    fun disconnect(sessionId: String): String {
        val entry = sessions.remove(sessionId)
            ?: return "Error: 会话 '$sessionId' 不存在。"
        try {
            entry.session.disconnect()
        } catch (_: Exception) {}
        return "SSH会话 $sessionId 已断开 (${entry.host}:${entry.port})"
    }

    /** List all active SSH sessions. */
    fun listSessions(): String {
        if (sessions.isEmpty()) return "当前没有活跃的SSH会话。"
        val arr = JSONArray()
        val toRemove = mutableListOf<String>()
        for ((id, entry) in sessions) {
            if (!entry.session.isConnected) {
                toRemove.add(id)
                continue
            }
            arr.put(JSONObject().apply {
                put("session_id", id)
                put("host", entry.host)
                put("port", entry.port)
                put("username", entry.username)
                put("connected_since", java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date(entry.createdAt)))
            })
        }
        // Clean up dead sessions
        toRemove.forEach { sessions.remove(it) }

        return if (arr.length() == 0) "当前没有活跃的SSH会话。"
        else arr.toString(2)
    }

    /** Disconnect all sessions — call on app shutdown. */
    fun disconnectAll() {
        for ((_, entry) in sessions) {
            try { entry.session.disconnect() } catch (_: Exception) {}
        }
        sessions.clear()
    }

    /** Recursively create remote directories. */
    private fun mkdirRecursive(channel: ChannelSftp, path: String) {
        val parts = path.split("/").filter { it.isNotBlank() }
        var current = if (path.startsWith("/")) "/" else ""
        for (part in parts) {
            current = if (current.isEmpty() || current == "/") "$current$part" else "$current/$part"
            try {
                channel.stat(current)
            } catch (_: SftpException) {
                try { channel.mkdir(current) } catch (_: SftpException) {}
            }
        }
    }
}
