package ai.localstudio.app.modelinstall

import ai.localstudio.model.RuntimeId
import ai.localstudio.model.install.DeviceProfile
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/**
 * The device side of admission and binding selection, read fresh on every
 * call — free space and free memory change between two installs.
 */
class AndroidDeviceProbe(private val context: Context) {

    /** Bytes an app may still write on the filesystem holding [directory] (StatFs.availableBytes). */
    fun freeBytes(directory: File): Long {
        var probe: File? = directory
        while (probe != null && !probe.exists()) probe = probe.parentFile
        return StatFs((probe ?: context.filesDir).path).availableBytes
    }

    /** ActivityManager's estimate of memory available without swapping/killing (MemoryInfo.availMem). */
    fun availableMemoryBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem
    }

    /** True when the system itself considers memory low right now. */
    fun isLowMemory(): Boolean {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.lowMemory
    }

    /** [runtimes]: what this build actually ships — the caller knows, the device doesn't. */
    fun profile(runtimes: Set<RuntimeId>): DeviceProfile = DeviceProfile(
        androidApi = Build.VERSION.SDK_INT,
        cpuFeatures = cpuFeatures(),
        hasGpu = false,
        hasNpu = false,
        runtimes = runtimes,
    )

    /** The "Features" line of /proc/cpuinfo (asimddp, i8mm, ...) — what the native variants are chosen by. */
    fun cpuFeatures(): Set<String> = runCatching {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features", ignoreCase = true) || it.startsWith("flags", ignoreCase = true) }
            .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
            .toSet()
    }.getOrDefault(emptySet())
}
