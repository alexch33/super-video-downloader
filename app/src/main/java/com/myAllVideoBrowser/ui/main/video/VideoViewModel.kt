package com.myAllVideoBrowser.ui.main.video

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SingleLiveEvent
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class VideoViewModel @Inject constructor(
    private val fileUtil: FileUtil,
) : BaseViewModel() {

    companion object {
        const val FILE_EXIST_ERROR_CODE = 1
        const val FILE_INVALID_ERROR_CODE = 2
    }

    private val _localVideos = MutableLiveData<List<LocalVideo>>(emptyList())
    val localVideos: LiveData<List<LocalVideo>> = _localVideos

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    val renameErrorEvent = SingleLiveEvent<Int>()
    val shareEvent = SingleLiveEvent<Uri>()

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000.milliseconds)
                val newList = getFilesList()
                val sorted = newList.sortedBy { it.uri }
                _localVideos.postValue(sorted)
            }
        }
    }

    override fun stop() {
    }

    private fun getFilesList(): List<LocalVideo> {
        val listVideos: MutableList<LocalVideo> = mutableListOf()
        val downloadsPath = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        } catch (e: Throwable) {
            ""
        }
        fileUtil.listFiles.forEach { entry ->
            val fileUri = entry.value.second
            val fileSize = fileUtil.getContentLength(ContextUtils.getApplicationContext(), fileUri)
            val readableSize = FileUtil.getFileSizeReadable(fileSize.toDouble())
            val isPrivate = try {
                val path = fileUri.path ?: ""
                path.isNotEmpty() && (downloadsPath.isEmpty() || !path.startsWith(downloadsPath))
            } catch (e: Throwable) {
                false
            }
            val video = LocalVideo(
                entry.value.first,
                fileUri,
                entry.key,
                isPrivate = isPrivate
            )
            video.size = readableSize
            listVideos.add(video)
        }

        return listVideos.toList()
    }

    fun deleteVideo(context: Context, video: LocalVideo) {
        viewModelScope.launch(Dispatchers.IO) {
            _localVideos.value?.find { it.uri.path == video.uri.path }?.let {
                fileUtil.deleteMedia(context, video.uri)

                val list = _localVideos.value?.toMutableList()
                list?.remove(it)
                _localVideos.postValue(list ?: mutableListOf())
            }
        }
    }

    fun renameVideo(context: Context, uri: Uri, newName: String) {
        if (newName.isNotEmpty()) {
            val exists = fileUtil.isUriExists(context, uri)
            if (exists) {
                val isFileWithNameNotExists =
                    fileUtil.isFileWithNameNotExists(context, uri, newName)
                if (isFileWithNameNotExists) {
                    val newMediaNameUri = fileUtil.renameMedia(context, uri, newName)
                    if (newMediaNameUri != null) {
                        _localVideos.value?.find { it.uri.toString() == uri.toString() }?.let {
                            it.uri = newMediaNameUri.second
                            it.name = newMediaNameUri.first

                            _localVideos.value?.let { list ->
                                val mutableList = list.toMutableList()
                                val index = mutableList.indexOf(it)
                                if (index != -1) {
                                    mutableList[index] = it
                                    _localVideos.postValue(mutableList)
                                }
                            }
                        }
                        return
                    }
                }

                renameErrorEvent.postValue(FILE_EXIST_ERROR_CODE)
                return
            }
        }

        renameErrorEvent.postValue(FILE_INVALID_ERROR_CODE)
    }

    fun setLoading(loading: Boolean) {
        _isLoading.postValue(loading)
    }

    fun findVideoByName(downloadFilename: String?): Observable<LocalVideo> {
        return Observable.create { emitter ->
            val videos = getFilesList()
            val found =
                videos.find { it.name.contains(File(downloadFilename.toString()).name) }
            if (found != null) {
                emitter.onNext(found)
                emitter.onComplete()
            }
        }
    }
}
