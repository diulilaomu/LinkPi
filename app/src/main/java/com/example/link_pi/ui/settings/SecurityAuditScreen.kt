package com.example.link_pi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dangerous
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.link_pi.data.TrustedCertStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SecurityAuditScreen() {
    val context = LocalContext.current
    val certStore = remember { TrustedCertStore(context) }
    var trustedCerts by remember { mutableStateOf(certStore.loadAll()) }
    var showProbeDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<TrustedCertStore.TrustedCert?>(null) }

    fun refresh() { trustedCerts = certStore.loadAll() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showProbeDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "探测证书")
            }
        }
    ) { padding ->
        if (trustedCerts.isEmpty()) {
            // ── Empty state ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无自定义信任证书",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "使用自签名证书的模块连接时需要手动信任",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Module connection audit ──
                item {
                    Text(
                        "已信任的证书",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                    )
                }

                items(trustedCerts, key = { it.sha256 }) { cert ->
                    TrustedCertCard(
                        cert = cert,
                        onRevoke = { revokeTarget = cert }
                    )
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // ── Probe dialog ──
    if (showProbeDialog) {
        ProbeDialog(
            certStore = certStore,
            onDismiss = { showProbeDialog = false },
            onTrusted = {
                showProbeDialog = false
                refresh()
            }
        )
    }

    // ── Revoke confirmation ──
    if (revokeTarget != null) {
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            icon = { Icon(Icons.Outlined.GppBad, contentDescription = null) },
            title = { Text("撤销信任") },
            text = {
                Column {
                    Text("确定不再信任此证书？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${revokeTarget!!.host}:${revokeTarget!!.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        revokeTarget!!.sha256.take(23) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    certStore.revoke(revokeTarget!!.sha256)
                    revokeTarget = null
                    refresh()
                }) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("取消") }
            }
        )
    }
}

// ── Trusted certificate card ──

@Composable
private fun TrustedCertCard(
    cert: TrustedCertStore.TrustedCert,
    onRevoke: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val isExpired = cert.notAfter > 0 && cert.notAfter < System.currentTimeMillis()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isExpired) Icons.Outlined.GppBad else Icons.Outlined.VerifiedUser,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .padding(top = 2.dp),
                tint = if (isExpired) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${cert.host}:${cert.port}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (cert.subjectDN.isNotBlank()) {
                    Text(
                        cert.subjectDN,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    cert.sha256.take(23) + "…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (cert.notAfter > 0) {
                    val expireText = if (isExpired) "已过期" else "有效期至 ${dateFormat.format(Date(cert.notAfter))}"
                    Text(
                        expireText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isExpired) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                Text(
                    "信任于 ${dateFormat.format(Date(cert.trustedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "撤销信任",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Probe dialog: connect to host, show cert, let user decide to trust ──

@Composable
private fun ProbeDialog(
    certStore: TrustedCertStore,
    onDismiss: () -> Unit,
    onTrusted: () -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<List<X509Certificate>?>(null) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!probing) onDismiss() },
        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        title = { Text("探测服务器证书") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (probeResult == null) {
                    // Input phase
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim(); probeError = null },
                        label = { Text("主机地址") },
                        placeholder = { Text("例如 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }; probeError = null },
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (probing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在连接…", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (probeError != null) {
                        Text(
                            probeError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    // Result phase — show certificate details
                    val leaf = probeResult!!.first()
                    val fp = TrustedCertStore.fingerprint(leaf)
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val alreadyTrusted = certStore.isTrusted(fp)

                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Dangerous,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (alreadyTrusted) "此证书已被信任" else "不受信任的证书",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (alreadyTrusted) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    CertDetailRow("主机", "$host:$port")
                    CertDetailRow("主题", leaf.subjectDN.name)
                    CertDetailRow("颁发者", leaf.issuerDN.name)
                    CertDetailRow("有效期", "${dateFormat.format(leaf.notBefore)} ~ ${dateFormat.format(leaf.notAfter)}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "SHA-256 指纹",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        fp,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (probeResult == null) {
                Button(
                    onClick = {
                        val portNum = port.toIntOrNull()
                        if (host.isBlank()) { probeError = "请输入主机地址"; return@Button }
                        if (portNum == null || portNum !in 1..65535) { probeError = "端口号无效"; return@Button }
                        probing = true
                        probeError = null
                        scope.launch {
                            try {
                                val certs = withContext(Dispatchers.IO) {
                                    TrustedCertStore.probeCertificates(host, portNum)
                                }
                                if (certs.isEmpty()) {
                                    probeError = "无法获取证书（连接失败或非 TLS 端口）"
                                } else {
                                    probeResult = certs
                                }
                            } catch (e: Exception) {
                                probeError = "连接失败: ${e.message}"
                            } finally {
                                probing = false
                            }
                        }
                    },
                    enabled = !probing
                ) { Text("探测") }
            } else {
                val leaf = probeResult!!.first()
                val fp = TrustedCertStore.fingerprint(leaf)
                val alreadyTrusted = certStore.isTrusted(fp)
                if (!alreadyTrusted) {
                    Button(onClick = {
                        val portNum = port.toIntOrNull() ?: 443
                        certStore.trust(
                            TrustedCertStore.TrustedCert(
                                sha256 = fp,
                                host = host,
                                port = portNum,
                                subjectDN = leaf.subjectDN.name,
                                issuerDN = leaf.issuerDN.name,
                                notBefore = leaf.notBefore.time,
                                notAfter = leaf.notAfter.time
                            )
                        )
                        onTrusted()
                    }) { Text("信任此证书") }
                } else {
                    TextButton(onClick = onDismiss) { Text("已信任") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (probeResult != null && probeError == null) {
                    // Back to input
                    probeResult = null
                } else {
                    onDismiss()
                }
            }) {
                Text(if (probeResult != null) "返回" else "取消")
            }
        }
    )
}

@Composable
private fun CertDetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
