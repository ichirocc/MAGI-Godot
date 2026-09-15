package com.magi.app.godot

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.magi.app.ui.MagiViewModel
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost

/**
 * Godot UI レイヤーのホストActivity＝アプリ唯一の起動入口（3.549.0 で Compose UI を削除）。
 * GodotFragment を埋め込み、GDScript 側からは JavaClassWrapper 経由で [MagiBridge] を叩く。
 *
 * 起動は [MagiStartupGuard] が見張る（3.550.0）: 前回の起動が画面に到達していなければ Godot を起動する前に
 * 診断画面（到達段階・Java 例外・Godot ログ・レンダラー切替）を出す。実機の logcat が得られない前提。
 */
class MagiGodotActivity : FragmentActivity(), GodotHost {

    companion object {
        // JavaClassWrapper から呼べるのは static メソッドだけなので、Activity が作った bridge を
        // ここ経由で公開する。onDestroy で外し、再生成前の Activity に紐づく古い参照を残さない。
        @Volatile
        private var bridge: MagiBridge? = null

        // guard は applicationContext だけを持つ（Activity を掴まない）。ui 到達の記録に使う。
        @Volatile
        private var guard: MagiStartupGuard? = null

        /** GDScriptの `JavaClassWrapper` から呼ぶための公開エントリ。 */
        @JvmStatic
        fun magiSnapshot(): String {
            guard?.markUiReached()
            return bridge?.snapshot() ?: MagiBridge.unavailableJson()
        }

        @JvmStatic
        fun magiDispatch(op: String, argsJson: String): String {
            guard?.markUiReached()
            return bridge?.dispatch(op, argsJson) ?: MagiBridge.unavailableJson()
        }

        // システムバー＋カットアウトの inset（px）。Godot の get_display_safe_area() はカットアウトしか返さないので、
        // ステータスバー/ナビバーの分は WindowInsets から渡す。GDScript 側（base_screen.gd）が余白にする。
        @Volatile
        private var insets: IntArray = intArrayOf(0, 0, 0, 0)

        @JvmStatic
        fun magiInsets(): String = insets.joinToString(",", "[", "]")
    }

    private var godotFragment: GodotFragment? = null
    private var commandLine: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = MagiStartupGuard(this).also { guard = it }
        g.installCrashHandler()
        val report = g.previousFailureReport()
        if (report != null) {
            showDiagnostics(g, report)
            return
        }
        try {
            launchGodot(g)
        } catch (t: Throwable) {
            // GodotFragment 内で拾われない例外（.so 読込失敗の UnsatisfiedLinkError 等）はここで画面に出す。
            g.record("launchGodot", t)
            showDiagnostics(g, g.previousFailureReport() ?: t.stackTraceToString())
        }
    }

    private fun launchGodot(g: MagiStartupGuard) {
        g.mark(MagiStartupGuard.STAGE_ACTIVITY)
        // 状態・操作のハブ MagiViewModel を ViewModelProvider から取得（Activity 再生成を跨いで保持）。
        val viewModel = ViewModelProvider(this)[MagiViewModel::class.java]
        bridge = MagiBridge(viewModel)
        g.mark(MagiStartupGuard.STAGE_VIEWMODEL)

        commandLine = buildList {
            add("--main-pack")
            // Gradle の exportGodotPck が assets/magi.pck に置いた Godot プロジェクト。filesDir へ複製した絶対パスを
            // 優先し、複製できなければ res://（Android では APK の assets）で読ませる。
            add(runCatching { g.stagedPckPath() }.getOrElse { e -> g.record("stagedPckPath", e); "res://${MagiStartupGuard.PCK_ASSET}" })
            if (g.useOpenGl) {
                // Godot 4.5 の Godot.kt は --rendering-method / --rendering-driver をコマンドラインから読んで
                // GL 用のレンダービューを選ぶ（ProjectSettings の mobile より優先）。
                add("--rendering-method"); add("gl_compatibility")
                add("--rendering-driver"); add("opengl3")
            }
            // Godot 自身のシステムバー余白（padding）を使わず、inset を GDScript 側の余白に一本化する。
            add("--edge_to_edge")
        }
        val fragment = GodotFragment()
        godotFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commitNow()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, wi ->
            val b = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insets = intArrayOf(b.left, b.top, b.right, b.bottom)
            wi
        }
        g.mark(MagiStartupGuard.STAGE_ENGINE)
    }

    /** Godot を起動せず、前回の停止情報と復帰操作だけを素の View で出す（スクリーンショットで共有できる）。 */
    private fun showDiagnostics(g: MagiStartupGuard, report: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "前回の起動が完了していません"
            textSize = 18f
        })
        column.addView(Button(this).apply {
            text = "そのまま起動"
            setOnClickListener { g.clear(); recreate() }
        })
        column.addView(Button(this).apply {
            text = if (g.useOpenGl) "標準レンダラー (Vulkan) で起動" else "OpenGL 互換レンダラーで起動"
            setOnClickListener { g.useOpenGl = !g.useOpenGl; g.clear(); recreate() }
        })
        column.addView(TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        })
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onDestroy() {
        bridge = null
        super.onDestroy()
    }

    override fun getActivity(): Activity = this

    override fun getGodot(): Godot? = godotFragment?.godot

    override fun getCommandLine(): List<String> = commandLine
}
