package com.personal.smartreply.data.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver get() = context.contentResolver

    fun getThreads(): List<SmsThread> {
        val threadMessages = mutableMapOf<String, MutableList<SmsMessage>>()

        // Read SMS messages only for thread listing (fast)
        readSmsMessages(threadMessages)

        return threadMessages.map { (threadId, messages) ->
            val latest = messages.first()
            // Collect all unique non-self addresses in this thread
            val allAddresses = messages
                .filter { !it.isFromMe }
                .map { it.address }
                .distinct()
                .ifEmpty { listOf(latest.address) }

            SmsThread(
                threadId = threadId,
                address = allAddresses.first(),
                contactName = null,
                lastMessage = latest.body,
                lastDate = latest.date,
                messageCount = messages.size,
                addresses = allAddresses
            )
        }.sortedByDescending { it.lastDate }
    }

    private fun readSmsMessages(threadMessages: MutableMap<String, MutableList<SmsMessage>>) {
        val uri = Uri.parse("content://sms/")
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        val cursor = contentResolver.query(
            uri, projection, null, null,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                val threadId = it.getString(threadIdx) ?: continue
                val address = it.getString(addressIdx) ?: continue
                val message = SmsMessage(
                    id = it.getLong(idIdx),
                    threadId = threadId,
                    address = address,
                    body = it.getString(bodyIdx) ?: "",
                    date = it.getLong(dateIdx),
                    isFromMe = it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT
                )
                threadMessages.getOrPut(threadId) { mutableListOf() }.add(message)
            }
        }
    }

    private fun getMmsBody(mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/part")
        val cursor = contentResolver.query(
            uri, arrayOf("_id", "ct", "text"), null, null, null
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val contentType = it.getString(it.getColumnIndexOrThrow("ct")) ?: continue
                if (contentType == "text/plain") {
                    return it.getString(it.getColumnIndexOrThrow("text"))
                }
            }
        }
        return null
    }

    private fun getMmsAddress(mmsId: Long, isFromMe: Boolean): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            uri, arrayOf("address", "type"), null, null, null
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow("address")) ?: continue
                val type = it.getInt(it.getColumnIndexOrThrow("type"))
                // type 137 = FROM, type 151 = TO
                // For outgoing: we want the TO address (the recipient)
                // For incoming: we want the FROM address (the sender)
                if (isFromMe && type == 151) return address
                if (!isFromMe && type == 137) return address
            }
        }
        return null
    }

    fun getMessagesForThread(threadId: String, limit: Int = 100): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        // Read SMS messages for this thread
        readSmsForThread(threadId, messages)

        // Read MMS messages for this thread
        readMmsForThread(threadId, messages)

        // Sort by date, deduplicate, and take latest
        val sorted = messages
            .sortedByDescending { it.date }
            .take(limit)
            .sortedBy { it.date }

        Log.d(TAG, "Thread $threadId: ${messages.size} total messages, returning ${sorted.size}. " +
                "Latest: ${sorted.lastOrNull()?.let { "${it.body.take(30)}... @ ${java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(it.date))}" } ?: "none"}")

        return sorted
    }

    private fun readSmsForThread(threadId: String, messages: MutableList<SmsMessage>) {
        val uri = Uri.parse("content://sms/")
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        val cursor = contentResolver.query(
            uri, projection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId),
            "${Telephony.Sms.DATE} DESC"
        ) ?: return

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                messages.add(
                    SmsMessage(
                        id = it.getLong(idIdx),
                        threadId = it.getString(threadIdx) ?: threadId,
                        address = it.getString(addressIdx) ?: "",
                        body = it.getString(bodyIdx) ?: "",
                        date = it.getLong(dateIdx),
                        isFromMe = it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT
                    )
                )
            }
        }
    }

    private fun readMmsForThread(threadId: String, messages: MutableList<SmsMessage>) {
        try {
            val uri = Telephony.Mms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX
            )

            val cursor = contentResolver.query(
                uri, projection,
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId),
                "${Telephony.Mms.DATE} DESC"
            ) ?: return

            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val msgBoxIdx = it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)

                while (it.moveToNext()) {
                    val mmsId = it.getLong(idIdx)
                    val date = it.getLong(dateIdx) * 1000 // MMS dates are in seconds
                    val msgBox = it.getInt(msgBoxIdx)
                    val isFromMe = msgBox == Telephony.Mms.MESSAGE_BOX_SENT

                    val body = getMmsBody(mmsId)
                    if (body.isNullOrBlank()) continue

                    val address = getMmsAddress(mmsId, isFromMe) ?: continue

                    // Skip duplicates (same timestamp within 2s and same body start)
                    val isDuplicate = messages.any { existing ->
                        Math.abs(existing.date - date) < 2000 &&
                        existing.body.take(20) == body.take(20)
                    }
                    if (isDuplicate) continue

                    messages.add(
                        SmsMessage(
                            id = mmsId + 1_000_000_000,
                            threadId = threadId,
                            address = address,
                            body = body,
                            date = date,
                            isFromMe = isFromMe
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read MMS for thread $threadId", e)
        }
    }

    companion object {
        private const val TAG = "SmsReader"
    }
}
