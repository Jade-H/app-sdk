package healthstack.wearable.support.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.paging.PagingSource
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import healthstack.common.room.dao.EcgDao
import healthstack.common.model.PrivDataType
import com.samsung.android.service.health.tracking.data.ValueKey.EcgSet
import healthstack.wearable.support.data.DataSender
import healthstack.wearable.support.data.pref.LastSyncTimePref
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class SyncPrivDataWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    @Inject
    lateinit var lastSyncTimePref: LastSyncTimePref
    @Inject
    lateinit var ecgDao: EcgDao
    @Inject
    lateinit var dataSender: DataSender

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        syncData().onFailure {
            Log.e(TAG, it.stackTraceToString())
            Log.e(TAG, it.message ?: "")
            return@withContext Result.failure()
        }

        return@withContext Result.success()
    }

    companion object {
        private val TAG = SyncPrivDataWorker::class.simpleName
        private const val PAGE_LOAD_SIZE = 1000
    }

    private suspend fun syncData(): kotlin.Result<Unit> = runCatching {
        var lastSyncKey = 0L
        lastSyncTimePref.getLastMeasureFlow().collect {
            lastSyncKey = it
        }
        var pageSource = ecgDao.getGreaterThan(lastSyncKey)
        var loadResult =
            pageSource.load(PagingSource.LoadParams.Refresh(null, PAGE_LOAD_SIZE, false))

        var nextPage: Int? = -1
        do {
            when (val copiedLoadResult = loadResult) {
                is PagingSource.LoadResult.Page -> {
                    if (copiedLoadResult.data.isEmpty() && nextPage == -1) break
                    Log.i(TAG, "${EcgSet::class.simpleName} data is loaded - page size: ${copiedLoadResult.data.size}")
                    dataSender.sendData(copiedLoadResult.data, PrivDataType.ECG).onSuccess {
                        lastSyncKey = copiedLoadResult.data.last().timestamp
                        nextPage = copiedLoadResult.nextKey
                        Log.i(TAG, "Data send success - ${EcgSet::class.simpleName}")
                    }.onFailure {
                        syncFinish(lastSyncKey)
                    }.getOrThrow()
                }

                is PagingSource.LoadResult.Error -> {
                    Log.e(TAG, copiedLoadResult.throwable.stackTraceToString())
                    syncFinish(lastSyncKey)
                    throw copiedLoadResult.throwable
                }

                is PagingSource.LoadResult.Invalid -> { // Database changes so Paging load returns Invalid results
                    Log.i(TAG, "Invalid page")
                    pageSource =
                        ecgDao.getGreaterThan(lastSyncKey) // Have to get PagingSource with data changed
                    loadResult =
                        pageSource.load(PagingSource.LoadParams.Refresh(null, PAGE_LOAD_SIZE, false))
                    continue
                }
            }

            if (nextPage == null) {
                syncFinish(lastSyncKey)
            } else {
                loadResult = pageSource.load(
                    PagingSource.LoadParams.Append(nextPage!!, PAGE_LOAD_SIZE, false)
                )
            }
        } while (nextPage != null)
    }

    private suspend fun syncFinish(
        lastSyncKey: Long,
    ) {
        lastSyncTimePref.add(lastSyncKey)
        ecgDao.deleteLEThan(lastSyncKey)
    }
}

fun setWearableDataSync(
    context: Context,
    syncInterval: Long,
    syncIntervalTimeUnit: TimeUnit,
) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "SyncPrivDataWorker",
        ExistingPeriodicWorkPolicy.REPLACE,
        PeriodicWorkRequestBuilder<SyncPrivDataWorker>(
            syncInterval, syncIntervalTimeUnit,
        ).build(),
    )
}
