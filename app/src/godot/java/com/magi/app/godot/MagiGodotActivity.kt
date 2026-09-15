package com.magi.app.godot

import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.magi.app.ui.MagiViewModel
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost

/**
 * [Godot移行] Godot UI レイヤーのホストActivity。magiGodot=true のビルドでは src/godot/AndroidManifest.xml
 * によりこちらがランチャーになる（Compose の MainActivity は残るが起動入口から外れる）。
 * GodotFragment を埋め込み、GDScript 側からは JavaClassWrapper 経由で [MagiBridge] を叩く。
 *
 * 未検証: このサンドボックスには Android SDK / Godot エンジン本体が無く、GodotFragment の実際の
 * 生成・GodotHost の契約は実機ビルドで初めて確認できる。docs/godot-ui-migration.md の「未実施・未検証」を参照。
 */
class MagiGodotActivity : FragmentActivity(), GodotHost {

    companion object {
        // JavaClassWrapper から呼べるのは static メソッドだけなので、Activity が作った bridge を
        // ここ経由で公開する。onDestroy で外し、再生成前の Activity に紐づく古い参照を残さない。
        @Volatile
        private var bridge: MagiBridge? = null

        /** GDScriptの `JavaClassWrapper` から呼ぶための公開エントリ。 */
        @JvmStatic
        fun magiSnapshot(): String = bridge?.snapshot() ?: MagiBridge.unavailableJson()

        @JvmStatic
        fun magiDispatch(op: String, argsJson: String): String =
            bridge?.dispatch(op, argsJson) ?: MagiBridge.unavailableJson()
    }

    private var godotFragment: GodotFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 既存Compose画面と同じ ViewModel を ViewModelProvider から取得（盤面・設定の二重管理を避ける）。
        val viewModel = ViewModelProvider(this)[MagiViewModel::class.java]
        bridge = MagiBridge(viewModel)

        val fragment = GodotFragment()
        godotFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commitNow()
    }

    override fun onDestroy() {
        bridge = null
        super.onDestroy()
    }

    override fun getActivity(): Activity = this

    override fun getGodot(): Godot? = godotFragment?.godot

    // Gradle の exportGodotPck が assets/magi.pck に置いた Godot プロジェクトを読ませる。
    // res:// は Android では APK の assets を指す（PCK 読込は project.godot より先に走るので res:// で可）。
    override fun getCommandLine(): List<String> = listOf("--main-pack", "res://magi.pck")
}
