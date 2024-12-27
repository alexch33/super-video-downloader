package com.myAllVideoBrowser.util

import android.widget.Toast
import com.myAllVideoBrowser.data.repository.AdBlockHostsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit

class AdsInitializerHelper {
    companion object {
        private const val ADS_LIST_UPDATE_TIME_DAYS = 7

        fun initializeAdBlocker(
            adBlockHostsRepository: AdBlockHostsRepository,
            sharedPrefHelper: SharedPrefHelper,
            lifecycleScope: CoroutineScope
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!sharedPrefHelper.getIsAdBlocker()) return@launch

                if (adBlockHostsRepository.getCachedCount() > 0) return@launch

                val isOutdated = isAdHostsOutdated(sharedPrefHelper)

                val (isInitialized, isUpdated) = if (isOutdated) {
                    updateAdHosts(adBlockHostsRepository, sharedPrefHelper)
                } else {
                    initializeAdHosts(adBlockHostsRepository)
                }

                withContext(Dispatchers.Main) {
                    showInitializationResultToast(isInitialized, isUpdated)
                }
            }
        }

        private fun isAdHostsOutdated(sharedPrefHelper: SharedPrefHelper): Boolean {
            val lastUpdateTime = sharedPrefHelper.getAdHostsUpdateTime()
            val currentTime = System.currentTimeMillis()
            val differenceInMillis = currentTime - lastUpdateTime
            val daysDifference = TimeUnit.MILLISECONDS.toDays(differenceInMillis)
            return daysDifference > ADS_LIST_UPDATE_TIME_DAYS
        }

        private suspend fun updateAdHosts(
            adBlockHostsRepository: AdBlockHostsRepository, sharedPrefHelper: SharedPrefHelper
        ): Pair<Boolean, Boolean> {
            AppLogger.d("HOST LIST OUTDATED, UPDATING...")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    ContextUtils.getApplicationContext(),
                    "AdBlock hosts lists start updating...",
                    Toast.LENGTH_LONG
                ).show()
            }
            val isInitialized = adBlockHostsRepository.initialize(true)
            return if (isInitialized) {
                sharedPrefHelper.setIsAdHostsUpdateTime(Date().time)
                AppLogger.d("HOST LISTS UPDATED DONE, TIME: ${Date()}")
                true to true
            } else {
                AppLogger.d("HOST LISTS UPDATED FAIL, TIME: ${Date()}")
                false to false
            }
        }

        private suspend fun initializeAdHosts(adBlockHostsRepository: AdBlockHostsRepository): Pair<Boolean, Boolean> {
            val isInitialized = adBlockHostsRepository.initialize(false)
            AppLogger.d("HOST LISTS INITIALIZED DONE, TIME: ${Date()}")
            return isInitialized to false
        }

        private fun showInitializationResultToast(isInitialized: Boolean, isUpdated: Boolean) {
            val message = when {
                isInitialized && !isUpdated -> "AdBlock hosts lists initialized"
                !isInitialized -> "AdBlock hosts lists initialized failed"
                else -> "AdBlock hosts lists initialized and updated"
            }
            Toast.makeText(
                ContextUtils.getApplicationContext(), message, Toast.LENGTH_LONG
            ).show()
        }
    }
}