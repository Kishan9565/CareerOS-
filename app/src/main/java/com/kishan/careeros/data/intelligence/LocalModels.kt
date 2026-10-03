package com.kishan.careeros.data.intelligence

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.kishan.careeros.domain.*
import com.kishan.careeros.domain.intelligence.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File


class AndroidDeviceCapability(private val context: Context): DeviceCapabilityChecker {
    override fun inspect(): DeviceCapability { val info = ActivityManager.MemoryInfo(); (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return DeviceCapability(info.totalMem, info.availMem, context.filesDir.usableSpace, "arm64-v8a" in Build.SUPPORTED_ABIS) }
}
class AndroidLocalModelManager(private val context: Context, private val checker: DeviceCapabilityChecker): LocalModelManager {
    private val preferences = context.getSharedPreferences("local_models", Context.MODE_PRIVATE)
    private val directory = File(context.noBackupFilesDir, "career_models")
    private val installer = ModelFileInstaller(directory)
    val inferenceMutex = Mutex()
    private val state = MutableStateFlow(ModelKind.entries.map { kind ->
        val installed = installer.hasFile(kind) && preferences.getString("hash_$kind", null) != null
        LocalModelStatus(kind, if (installed) ModelState.INSTALLED else ModelState.NOT_INSTALLED, version(kind), if (installed) "Imported; validate runtime on this device" else "Model not installed", installed)
    })
    override fun observe() = state.asStateFlow()
    override fun version(kind: ModelKind) = ModelCatalog.id(kind) + ":" + preferences.getString("hash_$kind", "absent")
    fun file(kind: ModelKind) = installer.file(kind)
    fun mark(kind: ModelKind, status: ModelState, message: String) {
        val installed = installer.hasFile(kind) && preferences.getString("hash_$kind", null) != null
        val effective = if (!installed && status in setOf(ModelState.INSTALLED, ModelState.READY)) ModelState.NOT_INSTALLED else status
        state.update { rows -> rows.map { if (it.kind == kind) LocalModelStatus(kind, effective, version(kind), message, installed) else it } }
    }
    fun failure(kind: ModelKind, e: Throwable) {
        val current = state.value.first { it.kind == kind }
        if (current.state == ModelState.UNSUPPORTED) return
        val message = "Fail: ${e.javaClass.simpleName}: ${e.message?.take(100) ?: "no message"}"
        mark(kind, ModelState.ERROR, message)
        val f = file(kind); val c = checker.inspect(); val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.e("IntelligenceDiagnostics", "VALIDATION FAILURE: $kind\nPath: ${f.absolutePath}\nExists: ${f.exists()}, Size: ${f.length()}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\nRAM: Total=${c.totalRam}, Avail=${c.availableRam}, Class=${am.memoryClass}/${am.largeMemoryClass}\nStorage: ${c.freeStorage}\nException: ${e.javaClass.name}\nMessage: ${e.message}\nCause: ${e.cause?.toString()}", e)
    }
    fun requireUsable(kind: ModelKind) {
        require(installer.hasFile(kind) && preferences.getString("hash_$kind", null) != null) { "Model not installed" }
        val c = checker.inspect(); val required = if (kind == ModelKind.LLM) 3_000_000_000L else 650_000_000L
        if (!c.arm64 || c.availableRam < required || c.freeStorage < 128_000_000) {
            val msg = "Needs arm64, ${required/1_000_000}MB RAM, 128MB storage. Has: ARM64=${c.arm64}, Avail=${c.availableRam/1_000_000}MB, Free=${c.freeStorage/1_000_000}MB"
            mark(kind, ModelState.UNSUPPORTED, msg)
            Log.w("IntelligenceDiagnostics", "Preflight rejected $kind: $msg")
            error("Insufficient device capability")
        }
    }
    internal fun logLlmInitialization(backend: String, maxTokens: Int) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val model = file(ModelKind.LLM)
        Log.d("IntelligenceDiagnostics", "LLM INITIALIZE path=${model.absolutePath} size=${model.length()} " +
            "abi=${Build.SUPPORTED_ABIS.joinToString()} process64Bit=${android.os.Process.is64Bit()} " +
            "availableRam=${info.availMem} totalRam=${info.totalMem} lowMemory=${info.lowMemory} " +
            "systemMemoryThreshold=${info.threshold} appRequiredAvailableRam=3000000000 " +
            "litertLmVersion=${GemmaRuntimeConfig.SDK_VERSION} backend=$backend maxTokens=$maxTokens " +
            "threadsRequested=unset nativeDefaultThreads=4 visionBackend=disabled audioBackend=disabled " +
            "cacheDir=model-directory")
    }
    override suspend fun importModel(kind: ModelKind, document: String, expectedSha256: String) = inferenceMutex.withLock { withContext(Dispatchers.IO) {
        var bytesCopied = 0L
        var scheme: String? = null
        try {
            val uri = Uri.parse(document)
            scheme = uri.scheme
            require(scheme == "content") { "Select a model using the Android document picker" }
            Log.i("IntelligenceDiagnostics", "IMPORT START kind=$kind scheme=$scheme destination=${file(kind).absolutePath}")
            val actual = installer.install(kind, expectedSha256,
                checkActive = { ensureActive() }, copied = { bytesCopied = it },
                checksumChecked = { expected, calculated, matches, copiedBytes, temporarySize ->
                    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        Log.d("IntelligenceDiagnostics", "IMPORT CHECKSUM kind=$kind expected=$expected actual=$calculated match=$matches bytes=$copiedBytes temporarySize=$temporarySize")
                    }
                }) {
                requireNotNull(context.contentResolver.openInputStream(uri)) { "Document provider could not open the selected model" }
            }
            check(preferences.edit().putString("hash_$kind", actual).commit()) { "Could not save verified model metadata; reimport the model" }
            mark(kind, ModelState.INSTALLED, "Checksum verified. Validate the runtime before use.")
            Log.i("IntelligenceDiagnostics", "IMPORT COMPLETE kind=$kind bytes=$bytesCopied checksum=match destination=${file(kind).absolutePath} exists=${file(kind).exists()} size=${file(kind).length()}")
            Unit
        } catch (e: CancellationException) {
            mark(kind, ModelState.ERROR, "Import cancelled; retry to finish installation.")
            throw e
        } catch (e: Exception) {
            // Provider exception messages can contain document URIs/names; expose only safe details.
            val detail = when (e) {
                is SecurityException -> "Document access was denied. Select the file again."
                is java.io.FileNotFoundException -> "The selected document could not be opened. Check that it is available locally."
                is java.nio.file.AtomicMoveNotSupportedException -> "Storage could not atomically install the model. Existing model preserved."
                is java.io.IOException -> "Storage or document read failed. Check the downloaded file and free storage."
                is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { it in setOf(
                    "Select a model using the Android document picker",
                    "Enter the SHA-256 from your trusted model download.",
                    "Cannot create model installation directory",
                    "Cannot remove incomplete model import",
                    "Document provider could not open the selected model",
                    "Model exceeds supported size",
                    "Insufficient storage for model import",
                    "Selected model is empty or copy is incomplete",
                    "Model checksum mismatch; existing model preserved",
                    "Installed model is missing or incomplete",
                    "Cannot clean up incomplete model import",
                    "Could not save verified model metadata; reimport the model",
                ) } ?: "Model verification or document provider failed. Select the file again."
                else -> "Model installation failed. Select the file again."
            }
            val message = "Import failed ($kind, ${e.javaClass.simpleName}): $detail"
            mark(kind, ModelState.ERROR, message)
            Log.e("IntelligenceDiagnostics", "$message scheme=$scheme destination=${file(kind).absolutePath} bytes=$bytesCopied exists=${file(kind).exists()} size=${file(kind).length()} cause=${e.cause?.javaClass?.name}")
            throw ModelImportException(message, e)
        }
    } }
    override suspend fun remove(kind: ModelKind) = inferenceMutex.withLock { withContext(Dispatchers.IO) { check(!file(kind).exists() || file(kind).delete()); check(preferences.edit().remove("hash_$kind").commit()); mark(kind, ModelState.NOT_INSTALLED, "Model removed") } }
}
