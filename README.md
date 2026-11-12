# Quiet Camera

iPhone 17 / iOS 26 の標準カメラを設計基準にした、Android 専用・静止画専用のカメラアプリ。
対象端末は OPPO Reno7 A（個人利用・APK 直接インストール、ストア配布なし）。

詳細設計書: [docs/QuietCamera_DesignDoc.md](docs/QuietCamera_DesignDoc.md)

## 技術構成

- Kotlin / Jetpack Compose / CameraX
- minSdk 30、compileSdk / targetSdk 36
- バージョンは `gradle/libs.versions.toml` で一元管理（AGP 9.5.1 / Kotlin 2.4.21 / CameraX 1.6.2 / Compose BOM 2026.09.00）

## 開発フェーズ（設計書 12 章）

| Phase | 内容 | 状態 |
|---|---|---|
| 0 | カメラ能力調査（設計書 2.3） | 実装済み・実機確認待ち |
| 1 | CameraX プレビュー | 未着手 |
| 2 | 撮影・保存 | 未着手 |
| 3 | 基本 UI | 未着手 |
| 4 | カメラ制御（ズーム・露出・フォーカス・フラッシュ） | 未着手 |
| 5 | Liquid Glass 風 UI・シャッター体験 | 未着手 |
| 6 | 最適化・画質比較 | 未着手 |

Phase 0 の調査結果は [docs/phase0-results.md](docs/phase0-results.md) に記録する。

## ビルド

Android Studio で開くか、コマンドラインで:

```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Phase 0 の実機確認手順

1. APK を Reno7 A にインストールして起動する
2. CAMERA 権限を許可する
3. 画面に表示される調査結果を確認する（「結果を共有」「コピー」で取り出し可能）
4. 「無音で撮影テスト」を押し、**システム撮影音が鳴るか**を耳で確認する
   （写真は `DCIM/QuietCamera` に `QC_PHASE0_...` として保存される）
5. 「振動テスト」3ボタンを押し、弱いクリックとして感じられるか確認する
