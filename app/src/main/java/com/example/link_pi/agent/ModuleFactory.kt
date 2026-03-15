package com.example.link_pi.agent

import org.json.JSONObject

/**
 * Module factory — creates module packages from server templates.
 *
 * Templates provide scaffold Python server scripts for common service types:
 * HTTP server, TCP server, UDP server.
 */
object ModuleFactory {

    data class Template(
        val id: String,
        val name: String,
        val description: String,
        val serviceType: String,
        val mainScript: String = "server.py",
        val scaffoldFiles: Map<String, String> = emptyMap(),
        val instructions: String = ""
    )

    val templates: Map<String, Template> by lazy { mapOf(
        "http_server" to Template(
            id = "http_server",
            name = "HTTP 服务",
            description = "Python HTTP 服务器，可定义任意 REST 路由",
            serviceType = "HTTP",
            scaffoldFiles = mapOf("server.py" to HTTP_SERVER_SCAFFOLD),
            instructions = "HTTP 服务模块。启动后监听 MODULE_PORT 端口，通过 call_module 发送 HTTP 请求调用。"
        ),
        "tcp_server" to Template(
            id = "tcp_server",
            name = "TCP 服务",
            description = "Python TCP 服务器，处理原始字节流连接",
            serviceType = "TCP",
            scaffoldFiles = mapOf("server.py" to TCP_SERVER_SCAFFOLD),
            instructions = "TCP 服务模块。启动后监听 MODULE_PORT 端口，接受 TCP 连接并处理数据。"
        ),
        "udp_server" to Template(
            id = "udp_server",
            name = "UDP 服务",
            description = "Python UDP 服务器，处理数据报",
            serviceType = "UDP",
            scaffoldFiles = mapOf("server.py" to UDP_SERVER_SCAFFOLD),
            instructions = "UDP 服务模块。启动后监听 MODULE_PORT 端口，接收 UDP 数据报并响应。"
        )
    ) }

    fun createFromTemplate(
        storage: ModuleStorage,
        templateId: String,
        name: String,
        overrides: Map<String, String> = emptyMap()
    ): ModuleStorage.Module? {
        val template = templates[templateId] ?: return null
        val port = overrides["port"]?.toIntOrNull() ?: 0
        val module = storage.create(
            name = name.ifBlank { template.name },
            description = overrides["description"] ?: template.description,
            serviceType = template.serviceType,
            defaultPort = port,
            mainScript = template.mainScript,
            instructions = template.instructions
        )
        for ((fileName, content) in template.scaffoldFiles) {
            storage.writeScript(module.id, fileName, content)
        }
        return module
    }

    fun listTemplatesJson(): String {
        val arr = org.json.JSONArray()
        for ((_, t) in templates) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("description", t.description)
                put("serviceType", t.serviceType)
            })
        }
        return arr.toString(2)
    }

    // ── Scaffold Python Server Scripts ──

    private val HTTP_SERVER_SCAFFOLD = """
\"\"\"HTTP 服务模块 — 基于 http.server 的轻量 REST 服务\"\"\"
import os
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = int(os.environ.get('MODULE_PORT', '8100'))
HOST = os.environ.get('MODULE_HOST', '0.0.0.0')


class Handler(BaseHTTPRequestHandler):
    \"\"\"自定义请求处理器 — 在这里定义你的路由和业务逻辑\"\"\"

    def do_GET(self):
        if self.path == '/' or self.path == '/health':
            self._json_response({'status': 'ok', 'message': 'Service is running'})
        elif self.path == '/hello':
            self._json_response({'hello': 'world'})
        else:
            self._json_response({'error': 'Not found'}, 404)

    def do_POST(self):
        body = self._read_body()
        if self.path == '/echo':
            self._json_response({'echo': body})
        elif self.path == '/process':
            # 在这里添加你的数据处理逻辑
            try:
                data = json.loads(body) if body else {}
                result = {'processed': True, 'input': data}
                self._json_response(result)
            except json.JSONDecodeError:
                self._json_response({'error': 'Invalid JSON'}, 400)
        else:
            self._json_response({'error': 'Not found'}, 404)

    def _read_body(self):
        length = int(self.headers.get('Content-Length', 0))
        return self.rfile.read(length).decode('utf-8') if length > 0 else ''

    def _json_response(self, data, code=200):
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass  # 静默日志


print(f'Starting HTTP server on {HOST}:{PORT}')
server = HTTPServer((HOST, PORT), Handler)
server.serve_forever()
""".trimIndent()

    private val TCP_SERVER_SCAFFOLD = """
\"\"\"TCP 服务模块 — 基于 socket 的 TCP 服务器\"\"\"
import os
import socket
import threading
import json

PORT = int(os.environ.get('MODULE_PORT', '8100'))
HOST = os.environ.get('MODULE_HOST', '0.0.0.0')


def handle_client(conn, addr):
    \"\"\"处理单个客户端连接 — 在这里定义你的业务逻辑\"\"\"
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break
            # 示例：回显收到的数据
            response = process_data(data)
            conn.sendall(response)
    except Exception as e:
        print(f'Client {addr} error: {e}')
    finally:
        conn.close()


def process_data(data):
    \"\"\"处理收到的数据并返回响应 — 修改此函数实现自定义逻辑\"\"\"
    try:
        # 尝试 JSON 解析
        text = data.decode('utf-8')
        parsed = json.loads(text)
        result = {'echo': parsed, 'processed': True}
        return json.dumps(result).encode('utf-8')
    except (json.JSONDecodeError, UnicodeDecodeError):
        # 二进制模式：原样回显
        return data


print(f'Starting TCP server on {HOST}:{PORT}')
server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind((HOST, PORT))
server.listen(5)

while True:
    conn, addr = server.accept()
    t = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
    t.start()
""".trimIndent()

    private val UDP_SERVER_SCAFFOLD = """
\"\"\"UDP 服务模块 — 基于 socket 的 UDP 数据报服务器\"\"\"
import os
import socket
import json

PORT = int(os.environ.get('MODULE_PORT', '8100'))
HOST = os.environ.get('MODULE_HOST', '0.0.0.0')


def process_datagram(data, addr):
    \"\"\"处理收到的数据报并返回响应 — 修改此函数实现自定义逻辑\"\"\"
    try:
        text = data.decode('utf-8')
        parsed = json.loads(text)
        result = {'echo': parsed, 'from': str(addr)}
        return json.dumps(result).encode('utf-8')
    except (json.JSONDecodeError, UnicodeDecodeError):
        return data


print(f'Starting UDP server on {HOST}:{PORT}')
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((HOST, PORT))

while True:
    data, addr = sock.recvfrom(4096)
    response = process_datagram(data, addr)
    sock.sendto(response, addr)
""".trimIndent()
}
