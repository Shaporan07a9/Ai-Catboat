package com.opendroid.ai.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A compact, Messenger "chat head" style window.
 *
 * Tapping the floating bubble opens THIS instead of [com.opendroid.ai.MainActivity] - the
 * user chats right where they are, without leaving whatever app is underneath. It is its
 * own small [WindowManager] overlay, driven directly by [AgentLoop] and
 * [ConversationRepository]; there is no Activity or ViewModel involved, since a
 * Service-hosted overlay has neither.
 */
class ChatBubbleWindow(
    private val context: Context,
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val scope: CoroutineScope
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var rootView: View? = null
    private var messagesContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var inputField: EditText? = null
    private var collectJob: Job? = null
    private var showing = false

    fun isShowing(): Boolean = showing

    fun toggle() {
        if (showing) hide() else show()
    }

    fun show() {
        if (showing) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#0D1117"))
                setStroke(dp(1), Color.parseColor("#2A2F36"))
            }
        }

        container.addView(buildHeader())

        val messages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        messagesContainer = messages

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
            addView(messages)
        }
        scrollView = scroll
        container.addView(scroll)

        container.addView(buildInputRow())

        rootView = container

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(220)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            windowManager.addView(container, params)
            showing = true
        } catch (e: Exception) {
            e.printStackTrace()
            rootView = null
            return
        }

        // Live-updates the bubble's message list the same way the in-app chat screen
        // does, just reading straight from the repository instead of a ViewModel.
        collectJob = scope.launch {
            conversationRepository.conversations.collectLatest { renderMessages(it) }
        }
    }

    fun hide() {
        if (!showing) return
        collectJob?.cancel()
        collectJob = null
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already removed.
            }
        }
        rootView = null
        messagesContainer = null
        scrollView = null
        inputField = null
        showing = false
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(10))

        addView(
            TextView(context).apply {
                text = "OpenDroid"
                setTextColor(Color.WHITE)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        addView(
            TextView(context).apply {
                text = "\u2715"
                setTextColor(Color.parseColor("#9AA4AF"))
                textSize = 16f
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener { hide() }
            }
        )
    }

    private fun buildInputRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(10))

        val input = EditText(context).apply {
            hint = "Message OpenDroid..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#161B22"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(8) }
        }
        inputField = input
        addView(input)

        addView(
            TextView(context).apply {
                text = "\u27A4"
                setTextColor(Color.parseColor("#00FF88"))
                textSize = 18f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { sendCurrentMessage() }
            }
        )
    }

    private fun sendCurrentMessage() {
        val text = inputField?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        inputField?.setText("")
        scope.launch {
            val sessionId = conversationRepository.ensureCurrentSessionId()
            agentLoop.processQuery(text, context, sessionId)
        }
    }

    private fun renderMessages(messages: List<ChatMessage>) {
        val container = messagesContainer ?: return
        container.removeAllViews()

        for (message in messages) {
            val isUser = message.sender == ChatMessage.Sender.USER

            val bubble = TextView(context).apply {
                text = message.text
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (isUser) Color.parseColor("#00783F") else Color.parseColor("#1C2128"))
                }
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (isUser) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                addView(
                    bubble,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = if (isUser) Gravity.END else Gravity.START }
                )
            }
            container.addView(row)
        }

        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }
}
