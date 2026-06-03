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
    @param:ApplicationContext private val context: Context,
    private val adBlockDao: AdBlockDao,
) {
    private var nativeEnginePtr: Long = 0
    private val native = AdBlockNative()
    private val engineScope = CoroutineScope(Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val cacheFile = File(context.filesDir, "adblock_engine.bin")
    private val cacheKeyFile = File(context.filesDir, "adblock_cache_key.txt")

    private val pointerLock = ReentrantReadWriteLock()

    fun loadRules() {
        engineScope.launch {
            reloadMutex.withLock {
                try {
                    val enabledLists = adBlockDao.getEnabledLists()
                    val currentKey = generateCacheKey(enabledLists)

                    // 1. Try to load from binary cache first (using file-to-native stream)
                    if (cacheFile.exists() && cacheKeyFile.exists()) {
                        val savedKey = try {
                            cacheKeyFile.readText()
                        } catch (e: Exception) {
                            ""
                        }
                        if (savedKey == currentKey) {
                            val ptr = native.createEngineFromBinaryFile(cacheFile.absolutePath)
                            if (ptr != 0L) {
                                updateNativePointer(ptr)
                                AppLogger.d("AdBlockEngine: Loaded successfully from binary cache file")
                                return@withLock
                            }
                        }
                    }

                    // 2. Fallback: Rebuild engine from files
                    AppLogger.d("AdBlockEngine: Rebuilding engine from rules files...")

                    val filePaths = mutableListOf<String>()

                    // Copy built-in rules to temp file to pass to native layer
                    try {
                        val assetFile = File(context.cacheDir, "easylist_tmp.txt")
                        context.assets.open("easyprivacylist.txt").use { input ->
                            assetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        filePaths.add(assetFile.absolutePath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        AppLogger.e("AdBlockEngine: Failed to prepare built-in easylist ${e.message}")
                    }

                    // Add custom rules files
                    enabledLists.forEach { list ->
                        list.localPath?.let { path ->
                            if (File(path).exists()) {
                                filePaths.add(path)
                            }
                        }
                    }

                    if (filePaths.isEmpty()) {
                        AppLogger.w("AdBlockEngine: No rules files found")
                        return@withLock
                    }

                    val newPtr = native.createEngineFromFiles(filePaths.toTypedArray())

                    if (newPtr != 0L) {
                        updateNativePointer(newPtr)
                        AppLogger.d("AdBlockEngine: New engine created successfully from files")

                        // Serialize the new engine directly to file to avoid OOM in JVM heap
                        try {
                            val success =
                                native.serializeEngineToFile(newPtr, cacheFile.absolutePath)
                            if (success) {
                                cacheKeyFile.writeText(currentKey)
                                AppLogger.d("AdBlockEngine: Binary cache file updated")
                            }
                        } catch (t: Throwable) {
                            AppLogger.e("AdBlockEngine: Serialization to file failed: ${t.message}")
                        }
                    } else {
                        AppLogger.e("AdBlockEngine: Failed to create engine from files")
                    }
                } catch (e: Exception) {
                    AppLogger.e("AdBlockEngine: loadRules failed: ${e.message}")
                }
            }
        }
    }

    private fun updateNativePointer(newPtr: Long) {
        pointerLock.writeLock().withLock {
            val oldPtr = nativeEnginePtr
            nativeEnginePtr = newPtr
            if (oldPtr != 0L) {
                native.destroyEngine(oldPtr)
            }
        }
    }

    private fun generateCacheKey(lists: List<AdBlockList>): String {
        val sb = StringBuilder("v22:")
        lists.sortedBy { it.id }.forEach {
            sb.append(it.id).append(":").append(it.lastUpdated).append(";")
        }
        return sb.toString()
    }

    fun isAd(url: String, sourceUrl: String = "", resourceType: String = "other"): Boolean {
        return try {
            pointerLock.readLock().withLock {
                if (nativeEnginePtr == 0L) return false

                if (url.length > 1024 || url.startsWith("data:")) {
                    return false
                }

                native.shouldBlock(
                    nativeEnginePtr,
                    url,
                    sourceUrl,
                    resourceType
                )
            }
        } catch (e: Throwable) {
            AppLogger.e("ADBLOCK ENGINE ERROR: ${e.message}")
            false
        }
    }

    protected fun finalize() {
        try {
            pointerLock.writeLock().withLock {
                if (nativeEnginePtr != 0L) {
                    native.destroyEngine(nativeEnginePtr)
                    nativeEnginePtr = 0L
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}

class AdBlockNative {
    companion object {
        init {
            System.loadLibrary("adblock_rust_jni")
        }
    }

    /**
     * Native methods updated to use File-to-File streaming
     * This completely bypasses the JVM heap for large data transfers.
     */
    external fun serializeEngineToFile(enginePtr: Long, filePath: String): Boolean
    external fun createEngineFromBinaryFile(filePath: String): Long
    external fun createEngineFromFiles(filePaths: Array<String>): Long

    // Checks if a URL is an ad
    external fun shouldBlock(
        enginePtr: Long,
        url: String,
        sourceUrl: String,
        resourceType: String
    ): Boolean

    external fun destroyEngine(enginePtr: Long)
}
