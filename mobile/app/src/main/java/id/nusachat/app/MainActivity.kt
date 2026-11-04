package id.nusachat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import id.nusachat.app.data.PushDeepLink
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.NusaChatApp
import id.nusachat.app.ui.theme.NusaChatTheme
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePushIntent(intent)
        enableEdgeToEdge()
        setContent {
            NusaChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NusaChatApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Aplikasi sudah hidup: teruskan langsung ke ViewModel yang sama.
        handleLiveIntent(intent)
    }

    private fun viewModel(): AppViewModel {
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        return ViewModelProvider(this, factory)[AppViewModel::class.java]
    }

    private fun handleLiveIntent(intent: Intent) {
        if (intent.getStringExtra("push_event") == "message") {
            intent.getStringExtra("push_conversation_id")?.toLongOrNull()?.let {
                if (it > 0) viewModel().openChatById(it)
            }
            return
        }
        val action = intent.getStringExtra("call_action") ?: return
        val callId = intent.getLongExtra("call_id", 0L)
        if (callId > 0) viewModel().onNotificationCallAction(action, callId)
    }

    private fun handlePushIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getStringExtra("push_event") == "message") {
            val id = intent.getStringExtra("push_conversation_id")?.toLongOrNull() ?: return
            if (id > 0) PushDeepLink.pendingConversationId = id
            return
        }
        val action = intent.getStringExtra("call_action") ?: return
        val callId = intent.getLongExtra("call_id", 0L)
        if (callId > 0) PushDeepLink.pendingCallAction = action to callId
    }
}
