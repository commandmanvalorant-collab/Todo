package com.todoglass

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*

object Store {
    class Item(val id: String, val text: String, var doneAt: Long)

    @Synchronized fun load(c: Context): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val a = org.json.JSONArray(c.getSharedPreferences("todo", 0).getString("items", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Item(o.getString("id"), o.getString("t"), o.optLong("d", 0L)))
            }
        } catch (e: Exception) {}
        return out
    }

    @Synchronized fun save(c: Context, l: List<Item>) {
        val a = org.json.JSONArray()
        for (x in l) a.put(org.json.JSONObject().put("id", x.id).put("t", x.text).put("d", x.doneAt))
        c.getSharedPreferences("todo", 0).edit().putString("items", a.toString()).apply()
    }

    @Synchronized fun add(c: Context, text: String) {
        val l = load(c)
        l.add(0, Item(System.nanoTime().toString(), text, 0L))
        save(c, l)
    }

    @Synchronized fun toggle(c: Context, id: String): Long {
        val l = load(c)
        val x = l.firstOrNull { it.id == id } ?: return 0L
        x.doneAt = if (x.doneAt == 0L) System.currentTimeMillis() else 0L
        save(c, l)
        return x.doneAt
    }

    @Synchronized fun purge(c: Context) {
        val now = System.currentTimeMillis()
        val l = load(c)
        if (l.removeAll { it.doneAt != 0L && now - it.doneAt >= 5000L }) save(c, l)
    }

    fun removeAfter(c: Context, id: String, at: Long, done: () -> Unit) {
        val app = c.applicationContext
        Thread {
            Thread.sleep(5000)
            synchronized(Store) {
                val l = load(app)
                if (l.removeAll { it.id == id && it.doneAt == at }) save(app, l)
            }
            Notifier.post(app)
            done()
        }.start()
    }
}

object Notifier {
    private fun views(c: Context, items: List<Store.Item>, max: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.notif)
        val rows = intArrayOf(R.id.r0, R.id.r1, R.id.r2, R.id.r3, R.id.r4)
        val boxes = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4)
        val texts = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)
        for (i in 0 until 5) {
            val show = i < max && (i < items.size || (items.isEmpty() && i == 0))
            if (!show) { v.setViewVisibility(rows[i], View.GONE); continue }
            v.setViewVisibility(rows[i], View.VISIBLE)
            if (items.isEmpty()) {
                v.setTextViewText(texts[i], "할 일이 없어요")
                v.setViewVisibility(boxes[i], View.INVISIBLE)
                continue
            }
            val item = items[i]
            val done = item.doneAt != 0L
            v.setViewVisibility(boxes[i], View.VISIBLE)
            v.setTextViewText(boxes[i], if (done) "☑" else "☐")
            val label = SpannableString(item.text)
            if (done) label.setSpan(StrikethroughSpan(), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.setTextViewText(texts[i], label)
            val pi = PendingIntent.getBroadcast(
                c, item.id.hashCode(),
                Intent(c, ActionReceiver::class.java).setAction("toggle")
                    .setData(Uri.parse("todo://toggle/" + item.id)).putExtra("id", item.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(boxes[i], pi)
            v.setOnClickPendingIntent(texts[i], pi)
        }
        return v
    }

    fun post(c: Context) {
        Store.purge(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("todo", "할 일", NotificationManager.IMPORTANCE_LOW))
        val items = Store.load(c)
        val ri = RemoteInput.Builder("txt").setLabel("할 일 입력").build()
        val addPi = PendingIntent.getBroadcast(
            c, 1, Intent(c, ActionReceiver::class.java).setAction("add"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val act = Notification.Action.Builder(
            Icon.createWithResource(c, android.R.drawable.ic_menu_add), "추가", addPi
        ).addRemoteInput(ri).build()
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val left = items.count { it.doneAt == 0L }
        val n = Notification.Builder(c, "todo")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(c, items, 2))
            .setCustomBigContentView(views(c, items, 5))
            .setSubText("남은 일 " + left + "개")
            .addAction(act)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        nm.notify(1, n)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            "add" -> {
                val t = RemoteInput.getResultsFromIntent(i)?.getCharSequence("txt")?.toString()?.trim()
                if (!t.isNullOrEmpty()) Store.add(c, t)
                Notifier.post(c)
            }
            "toggle" -> {
                val id = i.getStringExtra("id") ?: return
                val at = Store.toggle(c, id)
                Notifier.post(c)
                if (at != 0L) {
                    val pr = goAsync()
                    Store.removeAfter(c, id, at) { pr.finish() }
                }
            }
        }
    }
}

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var input: EditText

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun glass(r: Int) = GradientDrawable().apply {
        setColor(0x33FFFFFF)
        cornerRadius = dp(r).toFloat()
        setStroke(dp(1), 0x66FFFFFF)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            fitsSystemWindows = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3A2A6B.toInt(), 0xFF12335C.toInt(), 0xFF5A2A6E.toInt())
            )
        }
        val title = TextView(this).apply {
            text = "할 일"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(6), 0, dp(12))
        }
        root.addView(title)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(30)
            setPadding(dp(18), dp(6), dp(8), dp(6))
        }
        input = EditText(this).apply {
            hint = "할 일을 입력하세요"
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setSingleLine()
            background = null
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> add(); true }
        }
        val btn = Button(this).apply {
            text = "+"
            textSize = 22f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF7A7AFF.toInt()) }
            setOnClickListener { add() }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btn, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar)
        setContentView(root)
    }

    private fun add() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        Store.add(this, t)
        input.setText("")
        Notifier.post(this)
        render()
    }

    private fun render() {
        Store.purge(this)
        list.removeAllViews()
        for (item in Store.load(this)) {
            val done = item.doneAt != 0L
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(22)
                setPadding(dp(8), dp(10), dp(16), dp(10))
            }
            val box = TextView(this).apply {
                text = if (done) "☑" else "☐"
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setOnClickListener {
                    val at = Store.toggle(this@MainActivity, item.id)
                    Notifier.post(this@MainActivity)
                    render()
                    if (at != 0L) {
                        Store.removeAfter(this@MainActivity, item.id, at) { runOnUiThread { render() } }
                    }
                }
            }
            val label = TextView(this).apply {
                text = item.text
                textSize = 17f
                setTextColor(if (done) 0x99FFFFFF.toInt() else Color.WHITE)
                if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            row.addView(box, LinearLayout.LayoutParams(dp(48), dp(40)))
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(10)
            list.addView(row, lp)
        }
    }

    override fun onResume() {
        super.onResume()
        Notifier.post(this)
        render()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        Notifier.post(this)
    }
}
