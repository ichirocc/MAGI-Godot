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
 * Godot UI レイヤーのホストActivity＝アプリ唯一の起動入口（3.549.0 で Compose UI を削除）。
 * GodotFragment を埋め込み、GDScript 側からは JavaClassWrapper 経由で [MagiBridge] を叩く。
 *
 * コンパイル・PCK 同梱・Manifest 合流は CI（Godot UI Check）で確認済み。実機での起動は未確認
 * （docs/godot-ui-migration.md）。
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
        // 状態・操作のハブ MagiViewModel を ViewModelProvider から取得（Activity 再生成を跨いで保持）。
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
