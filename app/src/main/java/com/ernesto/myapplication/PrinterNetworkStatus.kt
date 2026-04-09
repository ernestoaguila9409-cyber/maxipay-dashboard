package com.ernesto.myapplication

import java.net.InetSocketAddress
import java.net.Socket

private const val PRINTER_RAW_PORT = 9100
private const val CONNECT_TIMEOUT_MS = 500

fun isPrinterOnline(ip: String): Boolean {
    var socket: Socket? = null
    return try {
        socket = Socket()
        socket.connect(InetSocketAddress(ip, PRINTER_RAW_PORT), CONNECT_TIMEOUT_MS)
        true
    } catch (_: Exception) {
        false
    } finally {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
