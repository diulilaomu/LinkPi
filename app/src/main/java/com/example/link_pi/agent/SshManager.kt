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
class SshManager private constructor(context: Context) {

    private val credentialStorage = CredentialStorage(context)

    companion object {
        private const val TAG = "SshManager"
        private val jsch = JSch()
        private val sessions = ConcurrentHashMap<String, SessionEntry>()

        @Volatile
        private var INSTANCE: SshManager? = null

        fun getInstance(context: Context): SshManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SshManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private data class SessionEntry(
        val session: Session,
        val host: String,
        val port: Int,
        val username: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** Public session info for UI display. */
    data class SshSessionInfo(
        val sessionId: String,
        val host: String,
        val port: Int,
        val username: String,
        val createdAt: Long
    )

    /** Get info for a specific session (for SSH mode UI). */
    fun getSessionInfo(sessionId: String): SshSessionInfo? {
        val entry = sessions[sessionId] ?: return null
        if (!entry.session.isConnected) {
            android.util.Log.w(TAG, "getSessionInfo: session $sessionId is disconnected, removing")
            sessions.remove(sessionId)
            return null
        }
        return SshSessionInfo(sessionId, entry.host, entry.port, entry.username, entry.createdAt)
    }

    /** Get all active session infos. */
    fun getActiveSessions(): List<SshSessionInfo> {
        android.util.Log.d(TAG, "getActiveSessions: sessions keys = ${sessions.keys}")
        val result = mutableListOf<SshSessionInfo>()
        val toRemove = mutableListOf<String>()
        for ((id, entry) in sessions) {
            if (!entry.session.isConnected) {
                android.util.Log.w(TAG, "getActiveSessions: session $id is disconnected")
                toRemove.add(id)
            } else {
                result.add(SshSessionInfo(id, entry.host, entry.port, entry.username, entry.createdAt))
            }
        }
        toRemove.forEach { sessions.remove(it) }
        return result
    }

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

            session.timeout = 0 // No socket read timeout (keepalive handles liveness)
            session.setServerAliveInterval(15_000) // Send keepalive every 15s
            session.setServerAliveCountMax(6)       // Disconnect after 6 missed keepalives (90s)
            session.connect(15_000) // 15s connect timeout

            val sessionId = "ssh_${System.currentTimeMillis().toString(36)}"
            sessions[sessionId] = SessionEntry(session, host, port, resolvedUser)
            android.util.Log.i(TAG, "connect: created session $sessionId for $resolvedUser@$host:$port, sessions=${sessions.keys}")

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

    /** Execute a command on an SSH session. Returns stdout/stderr.
     *  @param onOutput Called periodically with accumulated stdout so far (for streaming UI).
     */
    fun exec(sessionId: String, command: String, timeoutMs: Int = 300_000,
             onOutput: ((String) -> Unit)? = null): String {
        android.util.Log.d(TAG, "exec($sessionId, $command) — sessions keys: ${sessions.keys}")
        val entry = sessions[sessionId]
            ?: return "Error: 会话 '$sessionId' 不存在或已断开。请使用 ssh_connect 建立连接。(已知会话: ${sessions.keys})"
        if (!entry.session.isConnected) {
            android.util.Log.w(TAG, "exec: session $sessionId exists but isConnected=false")
            sessions.remove(sessionId)
            return "Error: 会话已断开，请重新连接。"
        }

        return try {
            val channel = entry.session.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val stdoutStream = channel.inputStream
            val stderrStream = channel.errStream

            channel.connect(30_000) // 30s connect timeout for the channel itself

            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val buffer = ByteArray(4096)
            var lastEmit = System.currentTimeMillis()

            val startTime = System.currentTimeMillis()
            while (!channel.isClosed || stdoutStream.available() > 0 || stderrStream.available() > 0) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    channel.disconnect()
                    return "Error: 命令执行超时 (${timeoutMs / 1000}s)\n已输出:\n${stdoutBuf.toString().takeLast(4000)}"
                }

                // Read stdout incrementally
                while (stdoutStream.available() > 0) {
                    val len = stdoutStream.read(buffer)
                    if (len > 0) {
                        stdoutBuf.append(String(buffer, 0, len, Charsets.UTF_8))
                    }
                }
                // Read stderr incrementally
                while (stderrStream.available() > 0) {
                    val len = stderrStream.read(buffer)
                    if (len > 0) {
                        stderrBuf.append(String(buffer, 0, len, Charsets.UTF_8))
                    }
                }

                // Stream live output to UI every 500ms
                val now = System.currentTimeMillis()
                if (onOutput != null && now - lastEmit > 500 && stdoutBuf.isNotEmpty()) {
                    lastEmit = now
                    onOutput(stdoutBuf.toString().takeLast(2000))
                }

                Thread.sleep(100)
            }

            // Final read
            while (stdoutStream.available() > 0) {
                val len = stdoutStream.read(buffer)
                if (len > 0) stdoutBuf.append(String(buffer, 0, len, Charsets.UTF_8))
            }
            while (stderrStream.available() > 0) {
                val len = stderrStream.read(buffer)
                if (len > 0) stderrBuf.append(String(buffer, 0, len, Charsets.UTF_8))
            }

            val exitCode = channel.exitStatus
            channel.disconnect()

            val out = stdoutBuf.toString()
            val err = stderrBuf.toString()

            // Limit output size
            val maxLen = 8000
            val truncatedOut = if (out.length > maxLen) out.takeLast(maxLen) + "\n...(输出已截断，仅保留末尾)" else out
            val truncatedErr = if (err.length > maxLen) err.takeLast(maxLen) + "\n...(错误输出已截断)" else err

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
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) "Error: 权限不足: 无法访问目录 $remotePath"
            else "Error: SFTP列表失败: ${e.message}"
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

    /** Download a remote file to a local file (binary-safe). */
    fun downloadToFile(sessionId: String, remotePath: String, localFile: java.io.File): Result<Long> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("会话不存在"))
        if (!entry.session.isConnected) { sessions.remove(sessionId); return Result.failure(Exception("会话已断开")) }
        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            localFile.parentFile?.mkdirs()
            channel.get(remotePath, java.io.FileOutputStream(localFile))
            channel.disconnect()
            Result.success(localFile.length())
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) Result.failure(Exception("权限不足: 无法读取文件 $remotePath"))
            else Result.failure(e)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Upload a local file to remote path (binary-safe). */
    fun uploadFromFile(sessionId: String, localFile: java.io.File, remotePath: String): Result<Long> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("会话不存在"))
        if (!entry.session.isConnected) { sessions.remove(sessionId); return Result.failure(Exception("会话已断开")) }
        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            val parentDir = remotePath.substringBeforeLast('/', "")
            if (parentDir.isNotBlank()) mkdirRecursive(channel, parentDir)
            channel.put(java.io.FileInputStream(localFile), remotePath)
            channel.disconnect()
            Result.success(localFile.length())
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) Result.failure(Exception("权限不足: 无法写入 $remotePath"))
            else Result.failure(e)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Read remote file content as String (for text editing; no size truncation). */
    fun readFileContent(sessionId: String, remotePath: String): Result<String> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("会话不存在"))
        if (!entry.session.isConnected) { sessions.remove(sessionId); return Result.failure(Exception("会话已断开")) }
        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            val output = ByteArrayOutputStream()
            channel.get(remotePath, output)
            channel.disconnect()
            Result.success(output.toString("UTF-8"))
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) Result.failure(Exception("权限不足: 无法读取文件 $remotePath"))
            else Result.failure(e)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Write string content to remote file. */
    fun writeFileContent(sessionId: String, remotePath: String, content: String): Result<Unit> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("会话不存在"))
        if (!entry.session.isConnected) { sessions.remove(sessionId); return Result.failure(Exception("会话已断开")) }
        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            val bytes = content.toByteArray(Charsets.UTF_8)
            channel.put(bytes.inputStream(), remotePath)
            channel.disconnect()
            Result.success(Unit)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) Result.failure(Exception("权限不足: 无法写入 $remotePath"))
            else Result.failure(e)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Delete a remote file. */
    fun deleteRemote(sessionId: String, remotePath: String): Result<Unit> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("会话不存在"))
        if (!entry.session.isConnected) { sessions.remove(sessionId); return Result.failure(Exception("会话已断开")) }
        return try {
            val channel = entry.session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            channel.rm(remotePath)
            channel.disconnect()
            Result.success(Unit)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) Result.failure(Exception("权限不足: 无法删除 $remotePath"))
            else Result.failure(e)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Delete remote file or directory (recursive). */
    fun deleteRecursive(sessionId: String, remotePath: String): Result<Unit> {
        return execCmd(sessionId, "rm -rf '${esc(remotePath)}'", "删除")
    }

    /** Copy remote file/dir to destination. */
    fun copyRemote(sessionId: String, src: String, dst: String): Result<Unit> {
        return execCmd(sessionId, "cp -r '${esc(src)}' '${esc(dst)}'", "复制")
    }

    /** Move remote file/dir to destination. */
    fun moveRemote(sessionId: String, src: String, dst: String): Result<Unit> {
        return execCmd(sessionId, "mv '${esc(src)}' '${esc(dst)}'", "移动")
    }

    private fun esc(s: String) = s.replace("'", "'\\''")

    private fun execCmd(sessionId: String, cmd: String, opName: String): Result<Unit> {
        val result = exec(sessionId, cmd, timeoutMs = 60_000)
        if (result.startsWith("Error:")) return Result.failure(Exception(result))
        return try {
            val json = JSONObject(result)
            val exitCode = json.optInt("exit_code", -1)
            if (exitCode != 0) {
                val stderr = json.optString("stderr", "").trim()
                val msg = when {
                    stderr.contains("Permission denied", true) -> "权限不足"
                    stderr.isNotBlank() -> stderr
                    else -> "${opName}失败 (exit=$exitCode)"
                }
                Result.failure(Exception(msg))
            } else Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ═══════════════════════════════════════
    //  SCP file transfer
    // ═══════════════════════════════════════

    /** Download a remote file via SCP protocol. */
    fun scpDownload(sessionId: String, remotePath: String, localFile: java.io.File): Result<Long> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("Error: 未找到会话"))
        return try {
            val channel = entry.session.openChannel("exec") as ChannelExec
            val escaped = remotePath.replace("'", "'\\''")
            channel.setCommand("scp -f '$escaped'")
            val outStream = channel.outputStream
            val inStream = channel.inputStream
            channel.connect(10_000)

            // Send ACK (0x00)
            outStream.write(0); outStream.flush()

            // First byte: 'C'=file header, 1=warning, 2=fatal error
            val firstByte = inStream.read()
            if (firstByte < 0) throw Exception("SCP流意外结束")

            // Error response from remote scp
            if (firstByte == 1 || firstByte == 2) {
                val errBuf = StringBuilder()
                while (true) {
                    val b = inStream.read()
                    if (b < 0 || b == 0x0A) break
                    errBuf.append(b.toChar())
                }
                channel.disconnect()
                val errMsg = errBuf.toString()
                return Result.failure(Exception(parseScpError(errMsg, remotePath)))
            }

            // Read rest of 'C' line: C<perms> <size> <filename>\n
            val headerBuf = StringBuilder()
            headerBuf.append(firstByte.toChar())
            while (true) {
                val b = inStream.read()
                if (b < 0) throw Exception("SCP流意外结束")
                if (b == 0x0A) break
                headerBuf.append(b.toChar())
            }
            val header = headerBuf.toString()
            if (!header.startsWith("C")) {
                channel.disconnect()
                return Result.failure(Exception(parseScpError(header, remotePath)))
            }
            val hParts = header.split(" ", limit = 3)
            val fileSize = hParts[1].toLong()

            // Send ACK
            outStream.write(0); outStream.flush()

            // Read file data
            localFile.parentFile?.mkdirs()
            localFile.outputStream().use { fos ->
                var remaining = fileSize
                val buf = ByteArray(8192)
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = inStream.read(buf, 0, toRead)
                    if (n < 0) throw Exception("SCP数据不完整")
                    fos.write(buf, 0, n)
                    remaining -= n
                }
            }

            // Read trailing 0x00 from scp
            inStream.read()
            // Send final ACK
            outStream.write(0); outStream.flush()

            channel.disconnect()
            Result.success(fileSize)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Upload a local file via SCP protocol. */
    fun scpUpload(sessionId: String, localFile: java.io.File, remotePath: String): Result<Long> {
        val entry = sessions[sessionId] ?: return Result.failure(Exception("Error: 未找到会话"))
        return try {
            val channel = entry.session.openChannel("exec") as ChannelExec
            val escaped = remotePath.replace("'", "'\\''")
            channel.setCommand("scp -t '$escaped'")
            val outStream = channel.outputStream
            val inStream = channel.inputStream
            channel.connect(10_000)

            // Wait for initial ACK
            val initAck = inStream.read()
            if (initAck != 0) {
                // Try to read error message
                val errBuf = StringBuilder()
                while (inStream.available() > 0) {
                    val b = inStream.read()
                    if (b < 0 || b == 0x0A) break
                    errBuf.append(b.toChar())
                }
                channel.disconnect()
                val errMsg = errBuf.toString()
                return Result.failure(Exception(parseScpError(errMsg.ifBlank { "远端拒绝" }, remotePath)))
            }

            // Send header: C0644 <size> <filename>\n
            val fileName = remotePath.substringAfterLast('/')
            val cmd = "C0644 ${localFile.length()} $fileName\n"
            outStream.write(cmd.toByteArray()); outStream.flush()

            // Wait for ACK
            val headerAck = inStream.read()
            if (headerAck != 0) {
                channel.disconnect()
                return Result.failure(Exception(parseScpError("远端拒绝写入文件", remotePath)))
            }

            // Send file data
            localFile.inputStream().use { fis ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    outStream.write(buf, 0, n)
                }
            }

            // Send trailing 0x00
            outStream.write(0); outStream.flush()

            // Wait for final ACK
            if (inStream.read() != 0) throw Exception("SCP传输确认失败")

            channel.disconnect()
            Result.success(localFile.length())
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Parse SCP error message into user-friendly text. */
    private fun parseScpError(msg: String, path: String): String {
        val lower = msg.lowercase()
        return when {
            lower.contains("permission denied") || lower.contains("not permitted") ->
                "权限不足: 无法访问 $path"
            lower.contains("not a regular file") ->
                "无法传输: $path 不是普通文件（可能是链接或目录）"
            lower.contains("no such file") ->
                "文件不存在: $path"
            else -> "SCP错误: $msg"
        }
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
