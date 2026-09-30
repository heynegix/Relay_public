package com.example.relay.rescue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.relay.RelayApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Retries Broker upload for a specific rescue envelope.
 * Unique work name: broker_retry_{requestId}_{requestVersion}
 * Exponential backoff: 30s initial, 15min max.
 */
class BrokerRetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? RelayApplication ?: return@withContext Result.failure()
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: return@withContext Result.failure()
        val requestVersion = inputData.getInt(KEY_REQUEST_VERSION, -1)
        if (requestVersion < 0) return@withContext Result.failure()

        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) return@withContext Result.failure()

        val record = app.rescueRepository.all()
            .find { it.envelope.requestId == requestId && it.envelope.requestVersion == requestVersion }
            ?: return@withContext Result.failure()

        val delivery = BrokerRescueDelivery(
            context = applicationContext,
            endpoint = endpoint,
            signingKeyStore = app.uploadSigningKeyStore,
        )

        when (val result = delivery.deliver(record.envelope)) {
            is BrokerDeliveryResult.Stored -> {
                app.database.brokerLedgerDao().markUploaded(
                    requestId = requestId,
                    requestVersion = requestVersion,
                    receiptId = result.response.brokerReceiptId,
                    uploadedAt = result.response.storedAtEpochMillis,
                )
                Result.success()
            }
            is BrokerDeliveryResult.Disabled -> Result.failure()
            is BrokerDeliveryResult.Offline -> {
                app.database.brokerLedgerDao().markRetrying(requestId, requestVersion)
                Result.retry()
            }
            is BrokerDeliveryResult.Failed -> {
                if (result.retryable) {
                    app.database.brokerLedgerDao().markRetrying(requestId, requestVersion)
                    Result.retry()
                } else {
                    app.database.brokerLedgerDao().markFailed(requestId, requestVersion)
                    Result.failure()
                }
            }
        }
    }

    companion object {
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_REQUEST_VERSION = "request_version"

        fun uniqueWorkName(requestId: String, requestVersion: Int): String =
            "broker_retry_${requestId}_$requestVersion"

        fun enqueue(context: Context, requestId: String, requestVersion: Int) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<BrokerRetryWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_REQUEST_ID to requestId,
                        KEY_REQUEST_VERSION to requestVersion,
                    ),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(requestId, requestVersion),
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
