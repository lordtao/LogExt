package ua.at.tsvetkov.logext.ui.logic

/**
 * Класс для управления списком процессов и маппингом PID -> Package.
 */
class ProcessManager {
    
    private val pidToProcess = mutableMapOf<String, String>()

    fun updateProcess(pid: String, pkg: String): Boolean {
        val changed = pidToProcess[pid] != pkg
        pidToProcess[pid] = pkg
        return changed
    }

    fun removeProcess(pid: String): Boolean {
        return pidToProcess.remove(pid) != null
    }

    fun getPackageByPid(pid: String): String? = pidToProcess[pid]

    fun getPackageByPidChecked(pid: String, expectedPackage: String): Boolean {
        return pidToProcess[pid] == expectedPackage
    }

    fun findPidByPackage(pkg: String): String? {
        return pidToProcess.entries.find { it.value == pkg }?.key
    }

    fun getAllPackages(): List<String> = pidToProcess.values.distinct().sorted()

    fun clear() {
        pidToProcess.clear()
    }
    
    fun getPids(): Set<String> = pidToProcess.keys.toSet()
}
