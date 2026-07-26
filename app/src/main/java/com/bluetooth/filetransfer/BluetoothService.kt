package com.bluetooth.filetransfer

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID

class BluetoothService : Service() {

    companion object {
        const val ACTION_START_SERVER = "com.bluetooth.filetransfer.START_SERVER"
        val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
        private const val TAG = "BtTransferService"
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SERVER) {
            startRfcommServer()
        }
        return START_STICKY
    }

    private fun startRfcommServer() {
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("BluetoothFileTransfer", MY_UUID)
                Log.d(TAG, "RFCOMM Server listening on UUID $MY_UUID")

                while (isRunning) {
                    val socket = serverSocket?.accept()
                    socket?.let {
                        handleConnectedSocket(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error", e)
            }
        }.start()
    }

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        Thread {
            try {
                val inputStream = DataInputStream(BufferedInputStream(socket.inputStream))
                val outputStream = DataOutputStream(BufferedOutputStream(socket.outputStream))

                while (socket.isConnected) {
                    val command = inputStream.readUTF()
                    when {
                        command.startsWith("LIST_DIR:") -> {
                            val targetPath = command.removePrefix("LIST_DIR:")
                            val dirListJson = getDirectoryListing(targetPath)
                            outputStream.writeUTF("LIST_RESP:$dirListJson")
                            outputStream.flush()
                        }
                        command.startsWith("PULL_FILE:") -> {
                            val filePath = command.removePrefix("PULL_FILE:")
                            sendFileOverSocket(filePath, outputStream)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket read error", e)
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun getDirectoryListing(relPath: String): String {
        val root = Environment.getExternalStorageDirectory()
        val targetDir = if (relPath.isEmpty() || relPath == "/") root else File(root, relPath)

        val jsonArr = JSONArray()
        if (targetDir.exists() && targetDir.isDirectory) {
            targetDir.listFiles()?.forEach { file ->
                val obj = JSONObject()
                obj.put("name", file.name)
                obj.put("isDirectory", file.isDirectory)
                obj.put("size", file.length())
                obj.put("lastModified", file.lastModified())
                jsonArr.put(obj)
            }
        }
        return jsonArr.toString()
    }

    private fun sendFileOverSocket(filePath: String, outputStream: DataOutputStream) {
        val file = File(Environment.getExternalStorageDirectory(), filePath)
        if (!file.exists() || !file.isFile) {
            outputStream.writeUTF("ERROR: File not found")
            outputStream.flush()
            return
        }

        outputStream.writeUTF("FILE_HEADER:${file.name}:${file.length()}")
        outputStream.flush()

        val buffer = ByteArray(8192)
        val fis = FileInputStream(file)
        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        outputStream.flush()
        fis.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
