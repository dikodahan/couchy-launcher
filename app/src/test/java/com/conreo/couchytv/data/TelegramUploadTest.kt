package com.conreo.couchytv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramUploadTest {
    private val pending = parse(
        """{"@type":"message","id":31,"chat_id":22,"sending_state":{"@type":"messageSendingStatePending"}}""",
    )

    @Test fun queuedMessageWaitsForMatchingServerConfirmation() = runBlocking {
        val updates = Channel<JsonObject>(4)
        val confirmation = async { awaitBackupUpload(pending, 22, updates) }
        updates.send(update("updateMessageSendSucceeded", 32, 22))
        updates.send(update("updateMessageSendSucceeded", 31, 23))
        yield()
        assertFalse(confirmation.isCompleted)
        updates.send(update("updateMessageSendSucceeded", 31, 22))
        confirmation.await()
    }

    @Test fun failureUpdateCanArriveBeforeTheSendResponseIsHandled() = runBlocking {
        val updates = Channel<JsonObject>(1)
        updates.send(update("updateMessageSendFailed", 31, 22))
        assertTrue(runCatching { awaitBackupUpload(pending, 22, updates) }.isFailure)
    }

    private fun update(type: String, id: Int, chat: Int) = parse(
        """{"@type":"$type","old_message_id":$id,"message":{"chat_id":$chat}}""",
    )

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
