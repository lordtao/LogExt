package ua.at.tsvetkov.logext.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Сервис для управления подпиской на логи Android LogCat.
 */
@Service(Service.Level.PROJECT)
class LogCatListenerService(private val project: Project) : Disposable {

    private var logcatThread: Thread? = null
    private var isDisposed = false

    /**
     * Возвращает список имен подключенных устройств.
     */
    fun getConnectedDevices(): List<String> {
        return try {
            val adbClass = Class.forName("com.android.ddmlib.AndroidDebugBridge")
            val bridge = adbClass.getMethod("getBridge").invoke(null)
            val devices = bridge?.javaClass?.getMethod("getDevices")?.invoke(bridge) as? Array<*>
            devices?.mapNotNull { device ->
                device?.javaClass?.getMethod("getName")?.invoke(device) as? String
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Запускает прослушивание логов для конкретного устройства.
     */
    fun startListening(deviceName: String?, callback: (String) -> Unit) {
        stopListening()
        
        try {
            val adbClass = Class.forName("com.android.ddmlib.AndroidDebugBridge")
            val bridge = adbClass.getMethod("getBridge").invoke(null) ?: return
            val devices = bridge.javaClass.getMethod("getDevices").invoke(bridge) as? Array<*> ?: return

            val device = if (deviceName == null) {
                devices.firstOrNull()
            } else {
                devices.firstOrNull { 
                    it?.javaClass?.getMethod("getName")?.invoke(it) == deviceName 
                }
            }

            if (device != null) {
                setupDeviceLogcat(device, callback)
            } else {
                callback("--- Device not found or not selected ---")
            }
        } catch (e: Exception) {
            callback("Error: ${e.message}")
            startMockLogs(callback)
        }
    }

    private fun setupDeviceLogcat(device: Any, callback: (String) -> Unit) {
        try {
            val receiverClass = Class.forName("com.android.ddmlib.IShellOutputReceiver")
            val proxyReceiver = java.lang.reflect.Proxy.newProxyInstance(
                receiverClass.classLoader,
                arrayOf(receiverClass)
            ) { _, method, args ->
                when (method.name) {
                    "addOutput" -> {
                        val data = args[0] as ByteArray
                        val offset = args[1] as Int
                        val length = args[2] as Int
                        val line = String(data, offset, length)
                        if (!isDisposed) callback(line)
                    }
                    "isCancelled" -> isDisposed
                    else -> null
                }
            }

            val executeMethod = device.javaClass.getMethod("executeShellCommand", String::class.java, receiverClass)
            
            logcatThread = Thread {
                try {
                    executeMethod.invoke(device, "logcat -v threadtime", proxyReceiver)
                } catch (e: Exception) {
                    if (!isDisposed) callback("Logcat stopped: ${e.message}")
                }
            }.apply { 
                isDaemon = true
                start() 
            }

        } catch (e: Exception) {
            callback("Failed to setup logcat receiver: ${e.message}")
        }
    }

    fun stopListening() {
        logcatThread?.interrupt()
        logcatThread = null
    }

    private fun startMockLogs(callback: (String) -> Unit) {
        callback("--- Mock Log Mode ---")
        callback("06-11 12:25:36.074  10011  3288 D PerfImpl: perfColdLaunchBoost: com.simplexsolutionsinc.vpn_unlimited")
    }

    override fun dispose() {
        isDisposed = true
        stopListening()
    }
}
