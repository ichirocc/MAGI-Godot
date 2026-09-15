package com.magi.app.godot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * [Godot移行/起動診断] 起動直後に落ちる不具合を logcat 無しで切り分けるための番人（3.550.0）。
 *
 * - 起動の段階（activity → viewmodel → engine → ui）を filesDir のマーカーに書く。ui は GDScript が
 *   最初に `magiSnapshot()` を呼んだ時点＝Godot の画面が Kotlin まで到達した証拠。
 * - 未捕捉の Java 例外はスタックトレースをファイルに残してから既定ハンドラ（プロセス終了）へ渡す。
 * - 次の起動で「前回 ui に到達していない」または「例外記録がある」なら、[MagiGodotActivity] は Godot を
 *   起動する前に診断画面を出す（内容はスクリーンショットで共有できる）。ネイティブクラッシュは Java 側で
 *   捕まえられないが、どの段階で止まったかはマーカーで分かる。
 * - PCK は assets から filesDir へ複製して絶対パスで `--main-pack` に渡す＝Godot 自身の拡張パック経路と同じ形
 *   （assets 内の圧縮エントリを AAsset で seek する経路を避ける）。
 */
class MagiStartupGuard(context: Context) {
    private val app: Context = context.applicationContext
    private val filesDir: File = app.filesDir
    private val stageFile = File(filesDir, STAGE_FILE)
    private val crashFile = File(filesDir, CRASH_FILE)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val uiMarked = AtomicBoolean(false)

    /** OpenGL 互換レンダラーで起動するか（診断画面の切替。Vulkan 初期化で落ちる端末の退避先）。 */
    var useOpenGl: Boolean
        get() = prefs.getBoolean(KEY_OPENGL, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENGL, value).apply()

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is RecordingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(RecordingHandler(previous))
    }

    private inner class RecordingHandler(private val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { record("uncaught exception on thread '${t.name}'", e) }
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** 例外をファイル先頭に追記する（古い記録は末尾に切り詰めて残す）。 */
    fun record(where: String, e: Throwable) {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val old = if (crashFile.exists()) crashFile.readText().take(KEEP_OLD_CHARS) else ""
        crashFile.writeText("${now()} $where\n$trace\n$old")
    }

    fun mark(stage: String) {
        runCatching { stageFile.writeText(stage) }
    }

    /** GDScript からの最初の呼出で 1 回だけ ui 段階を記録する（Godot のスレッドから呼ばれる）。 */
    fun markUiReached() {
        if (uiMarked.compareAndSet(false, true)) mark(STAGE_UI)
    }

    fun clear() {
        stageFile.delete()
        crashFile.delete()
        uiMarked.set(false)
    }

    /** 前回の起動に問題があれば診断テキストを返す。問題が無ければ null。 */
    fun previousFailureReport(): String? {
        val stage = stageFile.takeIf { it.exists() }?.readText()?.trim()
        val crash = crashFile.takeIf { it.exists() }?.readText()
        if ((stage == null || stage == STAGE_UI) && crash == null) return null
        return buildString {
            append("MAGI ").append(versionLabel()).append('\n')
            append("端末: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("レンダラー: ").append(if (useOpenGl) "OpenGL 互換" else "標準 (Vulkan / mobile)").append('\n')
            append("前回の到達段階: ").append(stage ?: "(記録なし)").append('\n')
            append("  ").append(explainStage(stage)).append('\n')
            append("PCK: ").append(pckStatus()).append('\n')
            if (crash != null) {
                append("\n--- Java 例外の記録 ---\n").append(crash.take(MAX_SHOWN_CHARS))
            } else {
                append("\nJava 例外の記録なし（ネイティブ側の停止、または OS による強制終了）\n")
            }
            val godotLog = latestGodotLog()
            if (godotLog != null) {
                append("\n--- Godot ログ (").append(godotLog.name).append(" 末尾) ---\n")
                append(godotLog.readText().takeLast(MAX_SHOWN_CHARS))
            } else {
                append("\nGodot ログなし（エンジンが project.godot を読む前に停止した可能性）\n")
            }
        }
    }

    /**
     * assets/magi.pck を filesDir/magi.pck へ複製し、その絶対パスを返す。APK が同じなら再複製しない
     * （判定は versionCode と APK ファイルの更新時刻）。失敗時は例外＝呼び出し側が res:// へ退避する。
     */
    fun stagedPckPath(): String {
        val out = File(filesDir, PCK_FILE)
        val stampFile = File(filesDir, PCK_STAMP)
        val stamp = pckStamp()
        if (out.exists() && stampFile.exists() && stampFile.readText() == stamp) return out.absolutePath
        val tmp = File(filesDir, "$PCK_FILE.tmp")
        app.assets.open(PCK_ASSET).use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "rename ${tmp.name} -> ${out.name} failed" }
        stampFile.writeText(stamp)
        return out.absolutePath
    }

    private fun pckStamp(): String {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return "${info.longVersionCode}:${File(app.applicationInfo.sourceDir).lastModified()}"
    }

    private fun pckStatus(): String = runCatching {
        val out = File(filesDir, PCK_FILE)
        val staged = if (out.exists()) "filesDir 複製 ${out.length()} bytes" else "filesDir 複製なし"
        val asset = app.assets.open(PCK_ASSET).use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
            total
        }
        "assets $asset bytes / $staged"
    }.getOrElse { "assets/magi.pck を開けない: $it" }

    private fun latestGodotLog(): File? =
        filesDir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.startsWith("godot") && it.name.endsWith(".log") }
            .maxByOrNull { it.lastModified() }

    private fun versionLabel(): String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("(version unknown)")

    private fun explainStage(stage: String?): String = when (stage) {
        STAGE_ACTIVITY -> "Activity 生成直後で停止（ViewModel 生成前）"
        STAGE_VIEWMODEL -> "ViewModel/ブリッジ生成後、Godot エンジン初期化中に停止（.so 読込・PCK 読込・レンダラー初期化）"
        STAGE_ENGINE -> "Godot エンジン初期化は完了、最初の画面が Kotlin を呼ぶ前に停止（描画開始・GDScript）"
        STAGE_UI, null -> "画面には到達している（例外記録を参照）"
        else -> "不明な段階"
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        const val STAGE_ACTIVITY = "activity"
        const val STAGE_VIEWMODEL = "viewmodel"
        const val STAGE_ENGINE = "engine"
        const val STAGE_UI = "ui"
        const val PCK_ASSET = "magi.pck"
        private const val PCK_FILE = "magi.pck"
        private const val PCK_STAMP = "magi.pck.stamp"
        private const val STAGE_FILE = "magi_startup_stage.txt"
        private const val CRASH_FILE = "magi_crash.txt"
        private const val PREFS = "magi_godot_launch"
        private const val KEY_OPENGL = "use_opengl"
        private const val KEEP_OLD_CHARS = 16_000
        private const val MAX_SHOWN_CHARS = 6_000
    }
}
