# Add project specific ProGuard rules here.

# 勤務表スキーマは不変・JSON/CSV入出力の要。R8/難読化で壊さない（改善仕様書 §8）。
# StateParser は org.json をキー名で読むため、モデルのクラス/プロパティ名を保持する。
-keep class com.magi.app.model.** { *; }
-keepclassmembers class com.magi.app.model.** { *; }

# Kotlin data class のメタデータ保持（型安全・シリアライズの担保）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
