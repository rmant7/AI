package ai.localstudio.core

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.Benchmarks
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind

const val GB: Long = 1_000_000_000

fun model(
    id: String,
    capabilities: Set<Capability> = setOf(Capability.TEXT_GENERATION, Capability.REASONING),
    bindings: List<RuntimeBinding> = listOf(binding()),
    benchmarks: Benchmarks = Benchmarks(general = 70.0),
    family: String = id.substringBefore('-'),
    version: String = "1.0",
    parameterCount: Long = 4_000_000_000,
) = ModelDescriptor(
    id = id,
    family = family,
    version = version,
    parameterCount = parameterCount,
    quantization = "Q4_K_M",
    contextLength = 32_768,
    languages = setOf("ru", "en"),
    capabilities = capabilities,
    bindings = bindings,
    benchmarks = benchmarks,
)

fun binding(
    runtime: RuntimeKind = RuntimeKind.LLAMA_CPP,
    ramBytes: Long = 3 * GB,
    fileSizeBytes: Long = 2 * GB,
    tps: Double? = 20.0,
    requiresGpu: Boolean = false,
    minAndroidApi: Int = 26,
) = RuntimeBinding(
    runtime = runtime,
    artifact = "$runtime-artifact",
    fileSizeBytes = fileSizeBytes,
    requiredRamBytes = ramBytes,
    requiresGpu = requiresGpu,
    minAndroidApi = minAndroidApi,
    referenceTokensPerSecond = tps,
)

/** ~12 GB RAM with 10 GB free — enough for one mid-size model, not for two. */
fun device(
    availableRamBytes: Long = 10 * GB,
    availableStorageBytes: Long = 40 * GB,
    supportedRuntimes: Set<RuntimeKind> = setOf(RuntimeKind.LLAMA_CPP, RuntimeKind.WHISPER_CPP),
    hasGpuDelegate: Boolean = false,
    androidApiLevel: Int = 35,
    performanceIndex: Double = 1.0,
) = DeviceProfile(
    totalRamBytes = 12 * GB,
    availableRamBytes = availableRamBytes,
    availableStorageBytes = availableStorageBytes,
    cpuCores = 8,
    androidApiLevel = androidApiLevel,
    supportedRuntimes = supportedRuntimes,
    hasGpuDelegate = hasGpuDelegate,
    performanceIndex = performanceIndex,
)
