# GlyphDayJP (Glyph Matrix Toy)

Nothing Phone の Glyph Matrix に「曜日の漢字」を表示するトイです。端末の日付に基づき、25×25 の円形領域内に当日の漢字（`日月火水木金土`）をアンチエイリアスで描画します。

## 機能

- **当日表示**: 端末日付から曜日の漢字を選択して表示
- **長押し**: 反転表示の切り替え（白黒反転）
- **シェイク**: 粒子が崩れ落ちるアニメーション（5秒後に当日の漢字に復帰）
- **AOD対応**: 常時表示時はアニメ停止・静止表示
- **自動更新**: 深夜に翌日の漢字へ自動切り替え

## 画面/構成

- `app/src/main/java/.../MainActivity.java`: エミュレータ用の簡易プレビュー UI
- `app/src/weekdayDevice/.../RippleWaveToyService.java`: 実機向けトイサービス（Glyph SDK連携）
- `app/src/main/AndroidManifest.xml`: トイ登録（`com.nothing.glyph.TOY`）/ 権限

## ビルド/インストール

1. Android Studio でプロジェクトを開く
2. `app/libs/` に Glyph Matrix SDK (`glyph-matrix-sdk-1.0.aar`) を配置
3. 実機ビルドで APK を作成し、Nothing Phone にインストール

## ライセンス

このプロジェクトは Glyph Matrix Developer Kit を使用して作成されています。
