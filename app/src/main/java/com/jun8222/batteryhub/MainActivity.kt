package com.jun8222.batteryhub

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { renderList(); handler.postDelayed(this, 5000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "배터리 허브"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "같은 와이파이에 연결된 내 폰·태블릿과 블루투스 이어폰의 배터리를 한곳에서 봐요. " +
                "모든 기기에 이 앱을 깔고 '동기화 시작'을 눌러 주세요."
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(18))
        })

        // 기기 이름
        root.addView(label("이 기기 이름"))
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nameEdit = EditText(this).apply {
            setText(BatteryStore.deviceName(this@MainActivity))
            isSingleLine = true
        }
        nameRow.addView(nameEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nameRow.addView(Button(this).apply {
            text = "저장"
            setOnClickListener {
                val n = nameEdit.text.toString().trim()
                if (n.isNotEmpty()) {
                    BatteryStore.setDeviceName(this@MainActivity, n)
                    if (BatteryStore.isRunning(this@MainActivity)) SyncService.start(this@MainActivity)
                    Toast.makeText(this@MainActivity, "저장했어요", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(nameRow)

        // 시작/중지
        toggle = Button(this).apply {
            setOnClickListener {
                if (BatteryStore.isRunning(this@MainActivity)) {
                    SyncService.stop(this@MainActivity)
                    updateToggle()
                } else {
                    askPermissionsThenStart()
                }
            }
        }
        root.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        root.addView(Button(this).apply {
            text = "배터리 최적화에서 제외하기 (권장)"
            setOnClickListener { requestIgnoreBatteryOptimization() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply { setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, 0) }
        root.addView(status)

        // 배터리 부족 알림 설정
        root.addView(label("배터리 부족 알림").apply { setPadding(0, dp(24), 0, dp(4)) })
        val thRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thRow.addView(TextView(this).apply { text = "기준 " })
        val thEdit = EditText(this).apply {
            setText(BatteryStore.threshold(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            gravity = Gravity.CENTER
        }
        thRow.addView(thEdit, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
        thRow.addView(TextView(this).apply { text = "% 이하" }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        thRow.addView(Button(this).apply {
            text = "저장"
            setOnClickListener {
                val v = thEdit.text.toString().toIntOrNull()
                if (v != null) {
                    BatteryStore.setThreshold(this@MainActivity, v)
                    thEdit.setText(BatteryStore.threshold(this@MainActivity).toString())
                    LowAlerts.check(this@MainActivity)
                    BatteryWidget.refresh(this@MainActivity)
                    renderList()
                    Toast.makeText(this@MainActivity, "저장했어요", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(thRow)
        root.addView(CheckBox(this).apply {
            text = "이 기기에서 부족 알림 받기"
            isChecked = BatteryStore.alertsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, c -> BatteryStore.setAlertsEnabled(this@MainActivity, c) }
        })

        root.addView(label("기기 목록").apply { setPadding(0, dp(24), 0, dp(2)) })
        root.addView(TextView(this).apply {
            text = "체크한 기기만 위젯에 항상 보여요. 체크 안 한 기기는 배터리가 기준 이하로 떨어지면 위젯에 ⚠ 표시로 나타나고 알림이 와요."
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(TextView(this).apply {
            text = "• 홈 화면을 길게 누르고 위젯 → '배터리 허브'에서 목록형·원형·가로형 중 골라 추가하세요. 추가한 뒤 길게 눌러 크기를 바꿀 수 있어요.\n" +
                "• 무선 이어폰은 그 이어폰이 연결된 기기에 '근처 기기' 권한을 허용해야 보여요.\n" +
                "• 기기·이어폰 체크 상태는 기기마다 따로 저장돼요 (태블릿 위젯은 태블릿에서 설정).\n" +
                "• 10분 넘게 소식이 없는 기기는 회색으로 마지막 시각을 표시하고, 3일 지나면 목록에서 사라져요.\n" +
                "• 공유기에서 '기기 간 통신 차단(AP 격리)'이 켜져 있으면 서로 보이지 않아요."
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        updateToggle()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun updateToggle() {
        val running = BatteryStore.isRunning(this)
        toggle.text = if (running) "동기화 중지" else "동기화 시작"
        status.text = if (running) "✅ 실행 중 — 1분마다, 그리고 잔량이 바뀔 때 다른 기기와 주고받아요."
        else "⏸ 꺼져 있어요"
        val pm = getSystemService(PowerManager::class.java)
        if (running && !pm.isIgnoringBatteryOptimizations(packageName)) {
            status.append("\n⚠️ 배터리 최적화가 켜져 있으면 화면이 꺼진 뒤 갱신이 멈출 수 있어요.")
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val list = BatteryStore.sorted(this)
        if (list.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "아직 기기가 없어요. 동기화를 시작해 보세요."
                setTextColor(Color.GRAY)
            })
            return
        }
        val me = BatteryStore.deviceId(this)
        val now = System.currentTimeMillis()
        for (e in list) {
            val stale = now - e.ts > BatteryWidget.STALE_MS
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(if (e.isAccessory) dp(20) else 0, dp(2), 0, dp(2))
            }
            row.addView(CheckBox(this).apply {
                isChecked = !BatteryStore.isHidden(this@MainActivity, e.key)
                setOnCheckedChangeListener { _, c ->
                    BatteryStore.setPinned(this@MainActivity, e.key, c)
                    LowAlerts.check(this@MainActivity)
                    BatteryWidget.refresh(this@MainActivity)
                }
            })
            val lowAlert = BatteryStore.isLowAlert(this, e)
            row.addView(TextView(this).apply {
                text = (if (lowAlert) "⚠ " else "") + (if (e.isAccessory) "🎧 " else "📱 ") + e.name +
                    (if (!e.isAccessory && e.owner == me) "  (이 기기)" else "")
                setTextColor(if (stale) Color.GRAY else Color.BLACK)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = e.level.coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(
                    if (stale) Color.GRAY else BatteryWidget.levelColor(e.level)
                )
            }, LinearLayout.LayoutParams(dp(70), dp(8)).apply { marginStart = dp(8) })
            row.addView(TextView(this).apply {
                text = buildString {
                    if (e.charging && !stale) append("⚡")
                    append(if (e.level >= 0) "${e.level}%" else "-")
                    if (stale) append("\n").append(BatteryWidget.ago(now - e.ts))
                }
                gravity = Gravity.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (stale) Color.GRAY else Color.BLACK)
            }, LinearLayout.LayoutParams(dp(70), WRAP_CONTENT))
            listBox.addView(row)
        }
    }

    private fun askPermissionsThenStart() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.BLUETOOTH_CONNECT
        if (need.isEmpty()) startSync() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startSync() // 권한을 거부해도 폰·태블릿 배터리 공유는 동작
    }

    private fun startSync() {
        SyncService.start(this)
        updateToggle()
        handler.postDelayed({ renderList() }, 1500)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "이미 제외되어 있어요", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
