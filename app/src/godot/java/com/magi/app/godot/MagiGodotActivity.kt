package com.magi.app.godot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.magi.app.ui.MagiViewModel
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotHost
import org.godotengine.godot.GodotFragment

/**
 * [Godot移行] Godot UI レイヤーのホストActivity。既存 MainActivity(Compose) とは別入口＝
 * ランチャーは変更せず、既存タスクは今まで通り MainActivity から起動する。
 * GodotFragment を埋め込み、GDScript 側からは JNI 経由で [MagiBridge] を叩く。
 *
 * 未検証: このサンドボックスには Android SDK / Godot エンジン本体が無く、GodotFragment の実際の
 * 生成・JNIシングルトン登録（Godot側の `register_singleton` 相当）は実機ビルドで初めて確認できる。
 * docs/godot-ui-migration.md の「未実施・未検証」を参照。
 */
class MagiGodotActivity : ComponentActivity(), GodotHost {

    private lateinit var bridge: MagiBridge
    private var godotFragment: GodotFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 既存Compose画面と同じ ViewModel インスタンスを取得（盤面・設定の二重管理を避ける）。
        val viewModel = ViewModelProvider(this)[MagiViewModel::class.java]
        bridge = MagiBridge(viewModel)

        val fragment = GodotFragment.newFragment()
        godotFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commitNow()
    }

    override fun getGodot(): Godot? = godotFragment?.godot

    /** GDScriptの `JavaClassWrapper` から呼ぶための公開エントリ。 */
    fun magiSnapshot(): String = bridge.snapshot()

    fun magiDispatch(op: String, argsJson: String): String = bridge.dispatch(op, argsJson)
}
