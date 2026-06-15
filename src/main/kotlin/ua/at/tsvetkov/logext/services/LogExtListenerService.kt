package ua.at.tsvetkov.logext.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Сервис для управления подпиской на логи Android LogExt.
 */
@Service(Service.Level.PROJECT)
class LogExtListenerService(private val project: Project) : Disposable {

    private var logcatThread: Thread? = null
    private var isDisposed = false
    private val deviceChangeListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        setupAdbListener()
    }

    private fun setupAdbListener() {
        try {
            val adbClass = Class.forName("com.android.ddmlib.AndroidDebugBridge")
            val addListenerMethod = adbClass.getMethod("addDeviceChangeListener", 
                Class.forName("com.android.ddmlib.AndroidDebugBridge\$IDeviceChangeListener"))
            
            val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                adbClass.classLoader,
                arrayOf(Class.forName("com.android.ddmlib.AndroidDebugBridge\$IDeviceChangeListener"))
            ) { _, _, _ ->
                deviceChangeListeners.forEach { it.invoke() }
                null
            }
            
            addListenerMethod.invoke(null, listenerProxy)
        } catch (_: Exception) {
        }
    }

    fun addDeviceChangeListener(listener: () -> Unit) {
        deviceChangeListeners.add(listener)
    }

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

    fun getProcessList(deviceName: String?): Map<String, String> {
        if (deviceName == null || deviceName == "Loading devices...") return emptyMap()
        return try {
            val adbClass = Class.forName("com.android.ddmlib.AndroidDebugBridge")
            val bridge = adbClass.getMethod("getBridge").invoke(null) ?: return emptyMap()
            val devices = bridge.javaClass.getMethod("getDevices").invoke(bridge) as? Array<*> ?: return emptyMap()
            
            val device = devices.firstOrNull { it?.javaClass?.getMethod("getName")?.invoke(it) == deviceName }
                ?: return emptyMap()

            val clients = device.javaClass.getMethod("getClients").invoke(device) as? Array<*> ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            
            clients.forEach { client ->
                if (client == null) return@forEach
                val data = client.javaClass.getMethod("getClientData").invoke(client)
                val pid = data?.javaClass?.getMethod("getPid")?.invoke(data)?.toString()
                val pkg = data?.javaClass?.getMethod("getPackageName")?.invoke(data) as? String
                val processName = data?.javaClass?.getMethod("getProcessName")?.invoke(data) as? String
                
                val name = pkg ?: processName
                if (pid != null && name != null) {
                    result[pid] = name
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

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
