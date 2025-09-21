# GlyphDayJP

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
- `app/src/main/java/.../WeekdayPreviewActivity.java`: 曜日プレビュー画面
- `app/src/main/AndroidManifest.xml`: トイ登録（`com.nothing.glyph.TOY`）/ 権限

## ビルド/インストール

1. Android Studio でプロジェクトを開く
2. `app/libs/` に Glyph Matrix SDK (`glyph-matrix-sdk-1.0.aar`) を配置
3. 実機ビルドで APK を作成し、Nothing Phone にインストール

## 使用方法

### 実機での使用
1. Nothing Phone の設定 > Glyph Interface > Glyph Toys で「GlyphDayJP」を選択
2. グリフボタンを短押ししてトイを切り替え
3. 長押しで白黒反転表示を切り替え
4. 端末をシェイクすると粒子アニメーションが開始

### エミュレータでの確認
1. アプリを起動して「曜日プレビュー」ボタンをタップ
2. 各曜日の漢字表示を確認可能

## 技術仕様

- **表示領域**: 25×25 ピクセル（円形マスク適用）
- **フォント**: システムデフォルトボールド
- **アンチエイリアス**: 有効
- **アニメーション**: 粒子落下エフェクト（40fps）
- **センサー**: 加速度センサー（シェイク検出）

## 開発環境

- **Android Studio**: Arctic Fox 以降
- **最小SDK**: API 21 (Android 5.0)
- **対象SDK**: API 34 (Android 14)
- **Glyph Matrix SDK**: 1.0

## ライセンス

このプロジェクトは Glyph Matrix Developer Kit を使用して作成されています。

## 貢献

バグ報告や機能要望は [Issues](https://github.com/yuk1-kondo/GlyphDayJP/issues) でお知らせください。

## 作者

[yuk1-kondo](https://github.com/yuk1-kondo)

---

**注意**: このトイは Nothing Phone の Glyph Matrix 機能を使用します。対応機種でのみ動作します。
