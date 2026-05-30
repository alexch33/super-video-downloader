package com.myAllVideoBrowser.ui.main.home.browser.adblocker

import android.content.Context
import com.myAllVideoBrowser.data.local.room.dao.AdBlockDao
import com.myAllVideoBrowser.data.local.room.entity.AdBlockList
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class AdBlockEngine @Inject constructor(
    @field:ApplicationContext private val context: Context,
    private val adBlockDao: AdBlockDao,
) {
    private var nativeEnginePtr: Long = 0
    private val native = AdBlockNative()
    private val engineScope = CoroutineScope(Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val cacheFile = File(context.filesDir, "adblock_engine.bin")
    private val cacheKeyFile = File(context.filesDir, "adblock_cache_key.txt")

    private val pointerLock = ReentrantReadWriteLock()

    init {
        loadRules()
    }

    fun loadRules() {
        engineScope.launch {
            reloadMutex.withLock {
                try {
                    val enabledLists = adBlockDao.getEnabledLists()
                    val currentKey = generateCacheKey(enabledLists)

                    // 1. Try to load from binary cache first
                    if (cacheFile.exists() && cacheKeyFile.exists()) {
                        val savedKey = try {
                            cacheKeyFile.readText()
                        } catch (e: Exception) {
                            ""
                        }
                        if (savedKey == currentKey) {
                            try {
                                val bytes = cacheFile.readBytes()
                                val ptr = native.createEngineFromBinary(bytes)
                                if (ptr != 0L) {
                                    updateNativePointer(ptr)
                                    AppLogger.d("AdBlockEngine: Loaded from binary cache")
                                    return@withLock
                                }
                            } catch (e: Throwable) {
                                AppLogger.e("AdBlockEngine: Binary cache load failed: ${e.message}")
                            }
                        }
                    }

                    // 2. Fallback: Rebuild from text rules
                    AppLogger.d("AdBlockEngine: Rebuilding engine from text rules...")

                    val isLocalEnabled = adBlockDao.getEnabledLists()
                        .firstOrNull { it.isDownloaded && it.localPath == null } != null
                    var rulesString: String? = buildCombinedRules(enabledLists, isLocalEnabled)

                    if (rulesString.isNullOrBlank()) {
                        AppLogger.w("AdBlockEngine: No rules found to load")
                        return@withLock
                    }

                    val newPtr = native.createEngine(rulesString)

                    rulesString = null

                    if (newPtr != 0L) {
                        updateNativePointer(newPtr)
                        AppLogger.d("AdBlockEngine: New engine created and pointer updated")

                        try {
                            val binary = try {
                                native.serializeEngine(newPtr)
                            } catch (_: OutOfMemoryError) {
                                AppLogger.e("AdBlockEngine: OOM during serialization, skipping cache write")
                                null
                            } catch (_: Throwable) {
                                AppLogger.e("AdBlockEngine: Error during serialization, skipping cache write")
                                null
                            }

                            if (binary != null && binary.isNotEmpty()) {
                                cacheFile.writeBytes(binary)
                                cacheKeyFile.writeText(currentKey)
                                AppLogger.d("AdBlockEngine: Binary cache updated")
                            }
                        } catch (e: Throwable) {
                            AppLogger.e("AdBlockEngine: Failed to serialize engine ${e.message}")
                        }
                    } else {
                        AppLogger.e("AdBlockEngine: Failed to create engine from rules string")
                    }
                } catch (e: Exception) {
                    AppLogger.e("AdBlockEngine: Error loading rules ${e.message}")
                }
            }
        }
    }

    private fun buildCombinedRules(
        enabledLists: List<AdBlockList>,
        isLocalEnabled: Boolean
    ): String {
        val allRules = StringBuilder()

        if (isLocalEnabled) {
            // Load built-in rules from assets
            try {
                val assetRules =
                    context.assets.open("easyprivacylist.txt").bufferedReader()
                        .use { it.readText() }
                allRules.append(assetRules).append("\n")
            } catch (e: Exception) {
                AppLogger.e("Failed to load easylist.txt from assets")
            }

        }
        // Load custom rules from files
        enabledLists.forEach { list ->
            list.localPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    try {
                        allRules.append(file.readText()).append("\n")
                    } catch (e: Exception) {
                        AppLogger.e("Failed to read rules from $path")
                    }
                }
            }
        }

        return allRules.toString()
    }

    private fun updateNativePointer(newPtr: Long) {
        pointerLock.writeLock().withLock{
            val oldPtr = nativeEnginePtr
            nativeEnginePtr = newPtr
            if (oldPtr != 0L) {
                // Destroying old engine while synchronized ensures no 'isAd' 
                // calls are currently using it.
                native.destroyEngine(oldPtr)
            }
        }
    }

    private fun generateCacheKey(lists: List<AdBlockList>): String {
        val sb = StringBuilder("v17:")
        lists.sortedBy { it.id }.forEach {
            sb.append(it.id).append(":").append(it.lastUpdated).append(";")
        }
        return sb.toString()
    }

    fun isAd(url: String, sourceUrl: String = "", resourceType: String = "other"): Boolean {
        return pointerLock.readLock().withLock {
            if (nativeEnginePtr == 0L) return false

            if (url.length > 2048 || url.startsWith("data:")) {
                return false
            }

            native.shouldBlock(
                nativeEnginePtr,
                url,
                sourceUrl,
                resourceType
            )
        }
    }

    protected fun finalize() {
        synchronized(this) {
            if (nativeEnginePtr != 0L) {
                native.destroyEngine(nativeEnginePtr)
                nativeEnginePtr = 0L
            }
        }
    }
}

class AdBlockNative {
    companion object {
        init {
            System.loadLibrary("adblock_rust_jni")
        }
    }

    external fun serializeEngine(enginePtr: Long): ByteArray
    external fun createEngineFromBinary(data: ByteArray): Long

    external fun createEngine(rules: String): Long

    // Checks if a URL is an ad
    external fun shouldBlock(
        enginePtr: Long,
        url: String,
        sourceUrl: String,
        resourceType: String
    ): Boolean

    external fun destroyEngine(enginePtr: Long)
}
