package com.example.link_pi.network

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Socket factory that enables TCP keepalive on every created socket.
 * This tells the OS to periodically send keepalive probes on idle connections,
 * preventing firewalls/NAT from closing them when the app is backgrounded.
 */
class KeepAliveSocketFactory : SocketFactory() {

    private val delegate = getDefault()

    override fun createSocket(): Socket =
        delegate.createSocket().apply { keepAlive = true }

    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).apply { keepAlive = true }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).apply { keepAlive = true }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).apply { keepAlive = true }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).apply { keepAlive = true }
}
