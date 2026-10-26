package app.cabinwright.store

import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The broker's question to the driver (design.md "Prompts"): an app wants a capability that
 * asks first. The service starts this (not exported, so only as root) with the extras
 * [EXTRA_REQUEST], [EXTRA_PACKAGE] (the NEMESIS id), [EXTRA_CAP], [EXTRA_DETAIL] and, if it
 * knows it, [EXTRA_NAME]; the answer goes back as `store/prompt_answer`. Unanswered, it
 * closes after [EXPIRE_S] seconds and the service counts that as no.
 */
class PromptActivity : StorePage() {
    private val ui = Handler()
    private val calls = Executors.newSingleThreadExecutor()
    private val choices = ArrayList<TextView>()
    private lateinit var question: TextView
    private lateinit var countdown: TextView
    private var requestId = ""
    private var cap = ""
    private var answered = false
    private var left = EXPIRE_S

    private val tick: Runnable = object : Runnable {
        override fun run() {
            left--
            if (left <= 0) {
                finish()
                return
            }
            countdown.text = closesIn()
            ui.postDelayed(this, SECOND_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST).orEmpty()
        cap = intent.getStringExtra(EXTRA_CAP).orEmpty()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        if (requestId.isEmpty() || cap.isEmpty()) {
            finish()
            return
        }
        val root = cap == "root"
        page.addView(View(this), fill())

        val card = panel()
        card.setPadding(dp(28), dp(20), dp(28), dp(20))
        card.addView(text("CABINWRIGHT · PERMISSION", 15, MUTED, false))
        question = text(ask(name.ifEmpty { pkg }.ifEmpty { "An app" }), 28, if (root) BAD else TEXT, false)
        question.setPadding(0, dp(10), 0, dp(6))
        card.addView(question)
        if (detail.isNotEmpty()) card.addView(text(detail, 18, TEXT, false))
        if (root) {
            val warn = text(ROOT_WARNING, 17, BAD, false)
            warn.setPadding(0, dp(8), 0, 0)
            card.addView(warn)
        }

        card.addView(choiceRow(root), wide())
        countdown = text(closesIn(), 15, MUTED, false)
        card.addView(countdown)
        page.addView(card, wide().apply { setMargins(dp(MARGIN_DP), 0, dp(MARGIN_DP), 0) })
        page.addView(View(this), fill())

        ui.postDelayed(tick, SECOND_MS)
        if (name.isEmpty() && pkg.isNotEmpty()) name(pkg)
    }

    /** The four answers, side by side and large; for root, the allowing ones in red. */
    private fun choiceRow(root: Boolean): View {
        val row = row()
        row.setPadding(0, dp(20), 0, dp(8))
        for ((label, choice) in CHOICES) {
            val color = when {
                choice == "deny" -> TEXT
                root -> BAD
                else -> ACCENT
            }
            val b = text(label, 20, color, false)
            b.gravity = Gravity.CENTER
            b.background = surface(dp(8))
            b.setOnClickListener { answer(choice) }
            choices.add(b)
            row.addView(
                b,
                LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f).apply {
                    leftMargin = dp(6)
                    rightMargin = dp(6)
                },
            )
        }
        return row
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        calls.shutdown()
        super.onDestroy()
    }

    private fun ask(app: String): String = app + " wants to " + capabilityWords(cap)

    private fun closesIn(): String = "Closes in $left s. No answer means no, this time."

    /** The app's name from the service, in place of its id once it's in. */
    private fun name(pkg: String) {
        calls.execute {
            val n = Daemon.status().value?.packages?.firstOrNull { it.id == pkg }?.name
            if (!n.isNullOrEmpty()) ui.post { question.text = ask(n) }
        }
    }

    private fun answer(choice: String) {
        if (answered) return
        answered = true
        ui.removeCallbacks(tick)
        for (b in choices) enable(b, false)
        calls.execute {
            Daemon.promptAnswer(requestId, choice)
            ui.post { finish() }
        }
    }

    companion object {
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_NAME = "name"
        const val EXTRA_CAP = "cap"
        const val EXTRA_DETAIL = "detail"
        private const val EXPIRE_S = 60
        private const val SECOND_MS = 1000L
        private const val BUTTON_DP = 72
        private const val MARGIN_DP = 64
        private const val ROOT_WARNING =
            "Root is full control of the head unit. Allow it only if you trust this app; " +
                "every command is logged on its page in the store."
        private val CHOICES = listOf(
            "Allow once" to "once",
            "Until the car is turned off" to "session",
            "Always" to "always",
            "Deny" to "deny",
        )
    }
}
