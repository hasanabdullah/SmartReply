# SmartReply

An Android app that uses the Claude API to suggest contextual SMS replies that match your personal texting style. Works as a floating overlay on top of Google Messages.

## How It Works

SmartReply runs as a floating overlay while you use Google Messages. When you open a conversation, the overlay automatically appears with a **Suggest Reply** button. It reads your conversation history, analyzes your texting style, and generates three categorized suggestions:

1. **Reply** -- directly responds to the most recent messages
2. **Follow-up** -- references something from 2-4 weeks back in the conversation that could use a check-in
3. **Personal** -- asks a genuine question about their life, family, work, hobbies, etc.

You can edit any suggestion before sending. The app learns from your edits to improve future suggestions.

## Key Features

- **Floating overlay** -- works on top of Google Messages without switching apps
- **Auto-send** -- tap the send button on a suggestion and it types and sends directly in Google Messages via the accessibility service
- **Style matching** -- mirrors your typical message length, punctuation, capitalization, slang, and emoji usage
- **Edit learning** -- tracks how you modify suggestions and feeds corrections back to Claude for future improvements (per-contact and global)
- **Conversation gap awareness** -- detects if you haven't replied in days/weeks/months and adjusts suggestions accordingly (e.g., acknowledges a late reply)
- **Group chat support** -- handles multi-person conversations with participant names
- **SMS + MMS reading** -- pulls messages from both SMS and MMS content providers for broader coverage
- **Configurable model** -- choose between Claude Haiku, Sonnet, or Opus
- **Custom tone description** -- describe your texting style in your own words to guide suggestions

## Architecture

MVVM with Jetpack Compose, built with:

- **Kotlin + Jetpack Compose** -- UI
- **Hilt** -- dependency injection
- **Room** -- local database for edit history
- **DataStore** -- user preferences (API key, model, tone)
- **Retrofit + OkHttp** -- Claude API calls
- **Accessibility Service** -- detects active Google Messages conversation and handles auto-send
- **Overlay Service** -- floating bubble and suggestion panel

```
├── data/
│   ├── contacts/       # Contact name resolution
│   ├── local/          # Room database, DataStore
│   ├── remote/         # Claude API client + streaming
│   └── sms/            # SMS/MMS content provider reader
├── repository/         # SmsRepository, EditHistoryRepository
├── service/
│   ├── ActiveConversationManager.kt
│   ├── OverlayService.kt                  # Floating UI
│   └── SmartReplyAccessibilityService.kt  # Google Messages integration
├── ui/
│   ├── components/     # MessageBubble, SuggestionCard, PermissionHandler
│   └── screens/        # Settings, Conversations, Chat, Compose
└── util/
    └── PromptBuilder.kt  # Claude prompt construction
```

## Setup

### Requirements

- Android 9+ (API 28)
- A Claude API key from [Anthropic](https://console.anthropic.com/)

### Build

```bash
git clone https://github.com/hasanabdullah/SmartReply.git
cd SmartReply
./gradlew installDebug
```

### Configure

1. Open SmartReply
2. Enter your Claude API key
3. Select a model (Haiku is fastest/cheapest, Opus is most capable)
4. Optionally describe your texting style (e.g., "casual, lowercase, rarely uses punctuation, uses bro a lot")
5. Grant permissions: SMS, Contacts, Overlay, Accessibility Service
6. Tap **Start Overlay**
7. Open Google Messages and enter a conversation

## Permissions

| Permission | Purpose |
|---|---|
| `READ_SMS` | Read conversation history for context |
| `READ_CONTACTS` | Resolve phone numbers to contact names |
| `INTERNET` | Call the Claude API |
| `SYSTEM_ALERT_WINDOW` | Display the floating overlay |
| `FOREGROUND_SERVICE` | Keep the overlay service running |
| Accessibility Service | Detect active conversation in Google Messages and auto-send messages |

## Privacy

- **No data leaves your device** except the conversation context sent to Claude's API when you tap "Suggest Reply"
- **No analytics, no tracking, no telemetry**
- Your API key is stored locally on-device in Android DataStore
- Edit history is stored locally in a Room database
- The app never stores or transmits your contacts, messages, or personal data to any server other than Anthropic's API at the moment you request suggestions
