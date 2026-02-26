package com.personal.smartreply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.personal.smartreply.MainActivity
import com.personal.smartreply.R
import com.personal.smartreply.data.local.Persona
import com.personal.smartreply.data.local.SettingsDataStore
import com.personal.smartreply.data.sms.SmsMessage
import com.personal.smartreply.data.sms.SmsThread
import com.personal.smartreply.repository.EditHistoryRepository
import com.personal.smartreply.repository.SmsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var smsRepository: SmsRepository
    @Inject lateinit var editHistoryRepository: EditHistoryRepository
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var activeConversationManager: ActiveConversationManager

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: ComposeView? = null
    private val composeOwner = OverlayComposeOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Compose state
    private val selectedPersona = mutableStateOf(Persona.CASUAL)
    private val selectedThread = mutableStateOf<SmsThread?>(null)
    private val messages = mutableStateOf<List<SmsMessage>>(emptyList())
    private val suggestions = mutableStateListOf<String>()
    private val isLoading = mutableStateOf(false)
    private val refreshingIndex = mutableStateOf(-1)
    private val error = mutableStateOf<String?>(null)
    private val participants = mutableStateOf<Map<String, String?>>(emptyMap())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        composeOwner.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        loadPersona()
        observeActiveConversation()
    }

    override fun onDestroy() {
        removePanel()
        removeBubble()
        composeOwner.onDestroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Persona ──────────────────────────────────────────────

    private fun loadPersona() {
        serviceScope.launch {
            settingsDataStore.persona.collect { name ->
                val p = try { Persona.valueOf(name) } catch (_: Exception) { Persona.CASUAL }
                selectedPersona.value = p
            }
        }
    }

    private fun setPersona(persona: Persona) {
        selectedPersona.value = persona
        serviceScope.launch {
            settingsDataStore.setPersona(persona.name)
        }
    }

    // ── Active conversation observer ─────────────────────────

    private fun observeActiveConversation() {
        serviceScope.launch {
            activeConversationManager.activeConversation.collect { conversation ->
                Log.d(TAG, "Active conversation changed: ${conversation?.contactName ?: "null"}" +
                    " scraped=${conversation?.scrapedMessages?.size ?: 0}")
                if (conversation != null) {
                    loadThreadAndShow(conversation.threadId, conversation.scrapedMessages)
                } else {
                    // Left the conversation — hide everything
                    Log.d(TAG, "Hiding overlay (no conversation)")
                    removePanel()
                    removeBubble()
                }
            }
        }
    }

    private fun loadThreadAndShow(threadId: String, scrapedMessages: List<ScrapedMessage> = emptyList()) {
        Log.d(TAG, "loadThreadAndShow: threadId=$threadId scraped=${scrapedMessages.size}")
        serviceScope.launch(Dispatchers.IO) {
            val allThreads = smsRepository.getThreads()
            val thread = allThreads.firstOrNull { it.threadId == threadId } ?: run {
                Log.d(TAG, "Thread $threadId not found in ${allThreads.size} threads")
                return@launch
            }

            Log.d(TAG, "Loaded thread: id=${thread.threadId} isGroup=${thread.isGroupChat} " +
                "addresses=${thread.addresses} contactName=${thread.contactName} " +
                "contactNames=${thread.contactNames} displayName=${thread.displayName}")

            // Build participant map
            val participantMap = thread.addresses.associateWith { addr ->
                thread.contactNames.getOrNull(thread.addresses.indexOf(addr))
                    ?.takeIf { it != addr }
            }

            // Load messages from SMS/MMS content provider
            val providerMsgs = if (!thread.isGroupChat) {
                smsRepository.getMergedMessagesForContact(thread.threadId, thread.address, participantMap)
            } else {
                smsRepository.getMessagesWithNames(thread.threadId, participantMap)
            }

            // If we have scraped messages from the UI, they represent the most recent
            // conversation (including RCS which isn't in the content provider).
            // Append them after provider messages so the AI sees the full picture.
            val msgs = if (scrapedMessages.isNotEmpty()) {
                val scrapedAsSms = scrapedMessages.map { scraped ->
                    SmsMessage(
                        id = scraped.hashCode().toLong(),
                        threadId = threadId,
                        address = if (scraped.isFromMe) "me" else scraped.sender,
                        body = scraped.body,
                        date = System.currentTimeMillis(), // recent — exact time doesn't matter for context
                        isFromMe = scraped.isFromMe,
                        senderName = if (scraped.isFromMe) null else scraped.sender
                    )
                }
                // Use provider messages for older context, scraped for recent
                // Take last N provider messages + all scraped messages
                val providerOlder = providerMsgs.takeLast(50)
                val combined = providerOlder + scrapedAsSms
                Log.d(TAG, "Combined messages: ${providerOlder.size} from provider + ${scrapedAsSms.size} scraped = ${combined.size}")
                combined
            } else {
                providerMsgs
            }

            Log.d(TAG, "Messages loaded: count=${msgs.size}, last5=${msgs.takeLast(5).map { "(${if (it.isFromMe) "me" else it.senderName ?: it.address}): ${it.body.take(30)}" }}")

            withContext(Dispatchers.Main) {
                selectedThread.value = thread
                participants.value = participantMap
                messages.value = msgs
                suggestions.clear()
                error.value = null

                // Show the bubble and panel automatically
                if (bubbleView == null) addBubble()
                if (panelView == null) showPanel()
            }
        }
    }

    // ── Bubble ──────────────────────────────────────────────

    private fun addBubble() {
        if (bubbleView != null) return

        val size = dpToPx(48)
        val bubble = android.widget.TextView(this).apply {
            text = "SR"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.ic_bubble)
            elevation = dpToPx(6).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(8)
            y = dpToPx(300)
        }

        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) togglePanel(); true }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun removeBubble() {
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
    }

    // ── Panel ───────────────────────────────────────────────

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) return
        if (selectedThread.value == null) return

        val panel = ComposeView(this).apply {
            setViewTreeLifecycleOwner(composeOwner)
            setViewTreeSavedStateRegistryOwner(composeOwner)
            setViewTreeViewModelStoreOwner(composeOwner)
            setContent {
                MaterialTheme {
                    PanelContent()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(panel, params)
        panelView = panel
        composeOwner.onResume()
    }

    private fun removePanel() {
        panelView?.let {
            composeOwner.onPause()
            windowManager.removeView(it)
        }
        panelView = null
        suggestions.clear()
        error.value = null
    }

    // ── Data ────────────────────────────────────────────────

    private fun suggestReply() {
        val thread = selectedThread.value ?: return

        serviceScope.launch {
            isLoading.value = true
            suggestions.clear()
            error.value = null

            // Always re-fetch messages so we have the latest conversation
            val participantMap = participants.value
            val freshMessages = withContext(Dispatchers.IO) {
                if (!thread.isGroupChat) {
                    smsRepository.getMergedMessagesForContact(thread.threadId, thread.address, participantMap)
                } else {
                    smsRepository.getMessagesWithNames(thread.threadId, participantMap)
                }
            }
            messages.value = freshMessages
            Log.d(TAG, "Refreshed messages: count=${freshMessages.size}, latest=${freshMessages.lastOrNull()?.date}")

            if (freshMessages.isEmpty()) {
                isLoading.value = false
                error.value = "No messages found"
                return@launch
            }

            val result = smsRepository.suggestReplies(
                freshMessages, thread.address, thread.contactName, participantMap
            )
            isLoading.value = false
            result.fold(
                onSuccess = { suggestions.addAll(it) },
                onFailure = { error.value = it.message }
            )
        }
    }

    private fun refreshSuggestion(index: Int) {
        val thread = selectedThread.value ?: return
        val msgs = messages.value
        if (msgs.isEmpty()) return

        val persona = selectedPersona.value
        val category = when (persona) {
            Persona.SPORTS_BRO -> when (index) {
                0 -> "a reply to recent context (last 15 messages if within 2 days, or last 3 if older), with a sports flavor or analogy"
                1 -> "a follow-up referencing something specific from more than 1 month ago in this conversation, with a sports angle"
                2 -> "a fresh sports-related topic or question, subtly informed by their interests but not directly referencing past conversations"
                else -> return
            }
            Persona.ECONOMIST -> when (index) {
                0 -> "a reply to recent context (last 15 messages if within 2 days, or last 3 if older), framed through an economic lens"
                1 -> "a follow-up referencing something specific from more than 1 month ago, considering tradeoffs or second-order effects"
                2 -> "a fresh economy-flavored topic or question, subtly informed by their interests but not directly referencing past conversations"
                else -> return
            }
            Persona.MUSLIM_PHILOSOPHER -> when (index) {
                0 -> "a reply to recent context (last 15 messages if within 2 days, or last 3 if older), with reflective wisdom and warmth"
                1 -> "a follow-up referencing something specific from more than 1 month ago, with a contemplative angle"
                2 -> "a fresh reflective topic or question that invites spiritual or philosophical reflection, subtly informed by their interests but not directly referencing past conversations"
                else -> return
            }
            Persona.CASUAL -> when (index) {
                0 -> "a reply to recent context (last 15 messages if within 2 days, or last 3 if older)"
                1 -> "a follow-up referencing something specific from more than 1 month ago in this conversation"
                2 -> "a fresh topic or question, subtly informed by their interests but not directly referencing past conversations"
                else -> return
            }
        }

        serviceScope.launch {
            refreshingIndex.value = index
            val result = smsRepository.suggestSingle(
                msgs, thread.address, thread.contactName, participants.value, category
            )
            refreshingIndex.value = -1
            result.fold(
                onSuccess = { if (index < suggestions.size) suggestions[index] = it },
                onFailure = { error.value = it.message }
            )
        }
    }

    private fun onUseSuggestion(original: String, edited: String) {
        val thread = selectedThread.value
        // Close panel first so Google Messages is fully visible
        removePanel()
        removeBubble()
        serviceScope.launch {
            // Wait for overlay to be fully removed and Google Messages to regain focus
            kotlinx.coroutines.delay(500)
            // Send via accessibility service
            activeConversationManager.sendMessage(edited)
            if (thread != null) {
                editHistoryRepository.saveEdit(thread.address, original, edited)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SmartReply", text))
        Toast.makeText(this, "Copied! Paste in your message app", Toast.LENGTH_SHORT).show()
    }

    // ── Compose UI ──────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PanelContent() {
        val currentThread by selectedThread
        val msgs by messages
        val loading by isLoading
        val err by error

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 16.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Header with conversation name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SmartReply", style = MaterialTheme.typography.titleMedium)
                        currentThread?.let { thread ->
                            Text(
                                text = thread.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { removePanel() }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Message preview
                if (msgs.isNotEmpty()) {
                    Text("Recent messages:", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    val isGroup = currentThread?.isGroupChat == true
                    msgs.takeLast(5).forEach { msg ->
                        val sender = when {
                            msg.isFromMe -> "Me"
                            isGroup -> msg.senderName ?: msg.address
                            else -> "Them"
                        }
                        Text(
                            text = "$sender: ${msg.body}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Persona selector
                val currentPersona by selectedPersona
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Persona.entries.forEach { persona ->
                        FilterChip(
                            selected = currentPersona == persona,
                            onClick = { setPersona(persona) },
                            label = { Text(persona.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Suggest button
                Button(
                    onClick = { suggestReply() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && currentThread != null
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Suggest Reply")
                    }
                }

                // Error
                err?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                // Suggestions
                val currentRefreshing by refreshingIndex
                suggestions.forEachIndexed { index, suggestion ->
                    Spacer(modifier = Modifier.height(8.dp))
                    OverlaySuggestionCard(
                        index = index,
                        suggestion = suggestion,
                        isRefreshing = currentRefreshing == index,
                        onRefresh = { refreshSuggestion(index) },
                        onUse = { original, edited -> onUseSuggestion(original, edited) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun OverlaySuggestionCard(
        index: Int,
        suggestion: String,
        isRefreshing: Boolean,
        onRefresh: () -> Unit,
        onUse: (original: String, edited: String) -> Unit
    ) {
        var editedText by remember(suggestion) { mutableStateOf(suggestion) }

        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                val label = when (index) {
                    0 -> "Reply"
                    1 -> "Follow-up"
                    2 -> "New Topic"
                    else -> "${index + 1}."
                }
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    enabled = !isRefreshing
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = { onRefresh() },
                        modifier = Modifier.size(36.dp),
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "New suggestion", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onUse(suggestion, editedText) },
                        modifier = Modifier.size(36.dp),
                        enabled = !isRefreshing
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun createNotification(): Notification {
        val channelId = "smartreply_overlay"
        val channel = NotificationChannel(channelId, "SmartReply Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, channelId)
            .setContentTitle("SmartReply active")
            .setContentText("Listening for conversations")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "SmartReplyOverlay"
        const val NOTIFICATION_ID = 1001
    }
}
