package com.personal.smartreply.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.smartreply.repository.SmsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmartReplyAccessibilityService : AccessibilityService() {

    @Inject lateinit var activeConversationManager: ActiveConversationManager
    @Inject lateinit var smsRepository: SmsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastDetectedTitle: String? = null
    private var observingMessages = false
    private var matchJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected")
        startObservingMessages()
        ensureOverlayServiceRunning()
    }

    private fun ensureOverlayServiceRunning() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            startForegroundService(intent)
            Log.i(TAG, "Started OverlayService")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start OverlayService", e)
        }
    }

    private fun startObservingMessages() {
        if (observingMessages) return
        observingMessages = true
        serviceScope.launch {
            Log.i(TAG, "Started observing pending messages")
            activeConversationManager.pendingMessage.collect { text ->
                Log.i(TAG, "Pending message to send: '${text.take(20)}...'")
                sendToGoogleMessages(text)
            }
        }
    }

    private suspend fun sendToGoogleMessages(text: String) {
        try {
            val root = rootInActiveWindow ?: run {
                Log.w(TAG, "No active window to send message")
                return
            }

            // Find the compose/text input field
            val composeField = findComposeField(root)
            if (composeField == null) {
                Log.w(TAG, "Could not find compose field")
                root.recycle()
                return
            }

            // Set the text in the compose field
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            composeField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            composeField.recycle()

            // Wait for UI to update — the send button changes from voice recorder
            // to send after text is entered, so we need to give it time
            delay(600)

            // Retry a few times since the UI tree may take a moment to update
            var sent = false
            for (attempt in 1..3) {
                val freshRoot = rootInActiveWindow ?: run {
                    Log.w(TAG, "Lost active window after setting text")
                    return
                }

                val sendButton = findSendButton(freshRoot)
                if (sendButton != null) {
                    sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Message sent successfully (attempt $attempt)")
                    sendButton.recycle()
                    freshRoot.recycle()
                    sent = true
                    break
                }
                freshRoot.recycle()
                Log.d(TAG, "Send button not found, attempt $attempt/3")
                delay(400)
            }
            if (!sent) {
                Log.w(TAG, "Could not find send button after 3 attempts")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }

    private fun findComposeField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for editable text field by resource ID
        val byId = findNodeByResourceId(root, "compose_message_text")
        if (byId != null) return byId

        // Fallback: find any editable text field in Google Messages
        return findEditableField(root)
    }

    private fun findEditableField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.className?.toString()?.contains("EditText") == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        // Also check for Compose text fields (they report as editable)
        if (node.isEditable && node.isFocusable) {
            val actions = node.actionList
            val hasSetText = actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            if (hasSetText) return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableField(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Google Messages Compose UI uses this resource ID for the send button
        val byId = findNodeByResourceId(root, "Compose:Draft:Send")
        if (byId != null) return byId

        // Fallback: try legacy resource IDs
        val legacyId = findNodeByResourceId(root, "send_message_button_container")
            ?: findNodeByResourceId(root, "send_message_button")
        if (legacyId != null) return legacyId

        // Fallback: find by content description containing "send"
        return findNodeByContentDescription(root, "send")
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, descSubstring: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null && desc.contains(descSubstring) && node.isClickable) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, descSubstring)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Ensure message observer is running (safety net if onServiceConnected didn't fire)
        startObservingMessages()

        val packageName = event.packageName?.toString() ?: return
        if (packageName == OWN_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (packageName == GOOGLE_MESSAGES_PACKAGE) {
                    activeConversationManager.setMessagesAppOpen(true)
                    // Check the class name — ConversationListActivity means we left a conversation
                    val className = event.className?.toString() ?: ""
                    if (className.contains("ConversationList") || className.contains("MainActivity")) {
                        // Went back to the conversation list — clear immediately
                        if (lastDetectedTitle != null) {
                            Log.i(TAG, "Back to conversation list (class: $className)")
                            matchJob?.cancel()
                            lastDetectedTitle = null
                            activeConversationManager.setActiveConversation(null)
                        }
                    } else {
                        tryExtractConversationTitle(allowClear = true)
                    }
                } else {
                    if (activeConversationManager.isMessagesAppOpen.value) {
                        val root = rootInActiveWindow
                        val rootPkg = root?.packageName?.toString()
                        root?.recycle()

                        if (rootPkg != null && rootPkg != GOOGLE_MESSAGES_PACKAGE
                            && rootPkg != OWN_PACKAGE) {
                            Log.i(TAG, "Left Messages (now: $rootPkg)")
                            matchJob?.cancel()
                            lastDetectedTitle = null
                            activeConversationManager.setMessagesAppOpen(false)
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (packageName == GOOGLE_MESSAGES_PACKAGE) {
                    // Only detect new conversations, never clear on content changes
                    // (title node can temporarily vanish during scrolls/animations)
                    tryExtractConversationTitle(allowClear = false)
                }
            }
        }
    }

    private fun tryExtractConversationTitle(allowClear: Boolean) {
        try {
            val root = rootInActiveWindow ?: return

            val title = findConversationTitle(root)
            root.recycle()

            if (title != null && title != lastDetectedTitle) {
                Log.i(TAG, "Conversation: '$title'")
                // Immediately clear old conversation so overlay hides while new one loads
                if (lastDetectedTitle != null) {
                    activeConversationManager.setActiveConversation(null)
                }
                lastDetectedTitle = title
                matchAndActivate(title)
            } else if (title == null && lastDetectedTitle != null && allowClear) {
                // Left conversation view
                Log.i(TAG, "Left conversation (was: '$lastDetectedTitle')")
                matchJob?.cancel()
                lastDetectedTitle = null
                activeConversationManager.setActiveConversation(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting title", e)
        }
    }

    private fun findConversationTitle(root: AccessibilityNodeInfo): String? {
        // Strategy 1: Find the top_app_bar_title_row node by traversing tree
        // (Compose test tags don't work with findAccessibilityNodeInfosByViewId)
        val titleRowNode = findNodeByResourceId(root, "top_app_bar_title_row")
        if (titleRowNode != null) {
            val name = findTextViewText(titleRowNode)
            titleRowNode.recycle()
            if (name != null && isLikelyContactName(name)) {
                return name
            }
        }

        // Strategy 2: Find top_app_bar node and extract TextView from it
        val topBarNode = findNodeByResourceId(root, "top_app_bar")
        if (topBarNode != null) {
            val name = findTextViewText(topBarNode)
            topBarNode.recycle()
            if (name != null && isLikelyContactName(name)) {
                return name
            }
        }

        // Strategy 3: Legacy "Navigate up, ContactName" content description
        val navUpTitle = findFromNavigateUp(root)
        if (navUpTitle != null) return navUpTitle

        return null
    }

    /**
     * Find a node by its resource-id (works for both View IDs and Compose test tags).
     * Traverses the tree checking viewIdResourceName.
     */
    private fun findNodeByResourceId(node: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        val nodeResId = node.viewIdResourceName
        if (nodeResId == resourceId || nodeResId == "$GOOGLE_MESSAGES_PACKAGE:id/$resourceId") {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByResourceId(child, resourceId)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * Find the first TextView text within a node subtree
     */
    private fun findTextViewText(node: AccessibilityNodeInfo): String? {
        if (node.className?.toString() == "android.widget.TextView") {
            val text = node.text?.toString()?.trim()
            if (text != null && text.isNotBlank()) return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextViewText(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * Legacy: Look for "Navigate up, ContactName" content description
     */
    private fun findFromNavigateUp(node: AccessibilityNodeInfo): String? {
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.startsWith("Navigate up")) {
            val commaIdx = desc.indexOf(", ")
            if (commaIdx >= 0) {
                val name = desc.substring(commaIdx + 2).trim()
                if (name.isNotBlank()) return name
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFromNavigateUp(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isLikelyContactName(text: String): Boolean {
        val lower = text.lowercase()
        if (lower in EXCLUDED_TITLES) return false
        if (text.length < 2 || text.length > 60) return false
        // Phone numbers are valid
        if (text.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }) {
            return text.count { it.isDigit() } >= 7
        }
        return true
    }

    private fun matchAndActivate(title: String) {
        // Cancel any in-flight lookup so stale results don't overwrite current
        matchJob?.cancel()

        // Scrape visible messages from the Google Messages UI tree
        val scraped = scrapeVisibleMessages()
        Log.i(TAG, "Scraped ${scraped.size} visible messages for '$title'")

        matchJob = serviceScope.launch {
            val thread = smsRepository.getThreadByContactName(title)
            // Verify the user hasn't navigated away while we were looking up
            if (lastDetectedTitle != title) {
                Log.i(TAG, "Title changed during lookup ('$title' -> '$lastDetectedTitle'), discarding")
                return@launch
            }
            if (thread != null) {
                Log.i(TAG, "Matched thread ${thread.threadId} for '$title'")
                activeConversationManager.setActiveConversation(
                    ActiveConversation(
                        threadId = thread.threadId,
                        contactName = thread.contactName,
                        scrapedMessages = scraped
                    )
                )
            } else {
                Log.i(TAG, "No thread match for '$title'")
                activeConversationManager.setActiveConversation(null)
            }
        }
    }

    /**
     * Scrape visible messages from the Google Messages accessibility tree.
     * Messages have resource-id ending in "message_text" and content-desc like:
     *   "Ammi said  Ok Wednesday 2:36 PM ."
     *   "You sent  Hey what's up Wednesday 3:00 PM ."
     */
    private fun scrapeVisibleMessages(): List<ScrapedMessage> {
        val messages = mutableListOf<ScrapedMessage>()
        try {
            val root = rootInActiveWindow ?: return messages
            collectMessages(root, messages)
            root.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scrape messages", e)
        }
        return messages
    }

    private fun collectMessages(node: AccessibilityNodeInfo, messages: MutableList<ScrapedMessage>) {
        val resId = node.viewIdResourceName
        val desc = node.contentDescription?.toString()
        val text = node.text?.toString()

        // Google Messages uses "message_text" resource ID for message content
        if (resId != null && resId.endsWith("message_text") && desc != null && text != null) {
            val parsed = parseMessageDesc(desc, text)
            if (parsed != null) {
                messages.add(parsed)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMessages(child, messages)
            child.recycle()
        }
    }

    /**
     * Parse the content-desc of a message node.
     * Formats observed:
     *   "{Name} said  {text} {day/date} {time} ."
     *   "You sent  {text} {day/date} {time} ."
     *   "{Name} said  {text} {day/date} {time} . Loved by {Name}"
     * We use the 'text' attribute for the actual body (more reliable).
     */
    private fun parseMessageDesc(desc: String, text: String): ScrapedMessage? {
        // Extract sender and time from content-desc
        val isFromMe = desc.startsWith("You sent") || desc.startsWith("You said")

        val sender: String
        val timeLabel: String

        if (isFromMe) {
            sender = "Me"
        } else {
            // "{Name} said  {text}..."
            val saidIdx = desc.indexOf(" said ")
            if (saidIdx < 0) return null
            sender = desc.substring(0, saidIdx)
        }

        // Extract time: look for common day/time patterns at the end
        // "... Wednesday 2:36 PM ." or "... 12/06/2024 2:36 PM ."
        val timeMatch = TIME_PATTERN.find(desc)
        timeLabel = timeMatch?.value?.trim()?.removeSuffix(".")?.trim() ?: ""

        // Use the text attribute as the body (it's the clean message text)
        val body = text.trim()
        if (body.isBlank()) return null

        return ScrapedMessage(
            sender = sender,
            body = body,
            isFromMe = isFromMe,
            timeLabel = timeLabel
        )
    }

    override fun onInterrupt() {
        lastDetectedTitle = null
    }

    override fun onDestroy() {
        activeConversationManager.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmartReplyA11y"
        const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
        const val OWN_PACKAGE = "com.personal.smartreply"

        private val EXCLUDED_TITLES = setOf(
            "messages", "google messages", "new conversation",
            "search", "settings", "start chat", "archived",
            "spam & blocked", "message organization",
            "rcs message"  // compose hint text
        )

        // Matches day/time patterns like "Wednesday 2:36 PM", "Yesterday 2:36 PM", "12/06/2024 2:36 PM"
        private val TIME_PATTERN = Regex(
            """(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Yesterday|Today|\d{1,2}/\d{1,2}(?:/\d{2,4})?)\s+\d{1,2}:\d{2}\s*[AP]M\s*\.?(?:\s*(?:Loved|Liked|Laughed|Emphasized|Questioned|Disliked)\s+by\s+.+)?$"""
        )
    }
}
