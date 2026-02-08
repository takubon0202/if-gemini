# GeminiNPC - Minecraft AI相談システム

MinecraftサーバーでGoogle Gemini AIを使った相談システムとWeb検索機能を提供するSpigotプラグインです。

## 機能

- `/gemini` コマンドでシステムを起動し、メインメニューから各機能にアクセス
- **相談モード** - AIカウンセラーに相談（400文字以内で回答）
- **Web検索機能** - リアルタイムWeb検索
- **3種類のAIモデル** - 用途に応じて選択可能
- カウンセリングマインドな回答スタイル（共感→要約→アクション提案）
- 会話履歴を保持し、文脈を理解した対話が可能

## 動作環境

- Minecraft 1.21.11+
- Spigot / Paper サーバー
- Java 21+

## インストール

1. [Releases](https://github.com/Getabako/minecraftAI/releases)からJARファイルをダウンロード
2. サーバーの `plugins` フォルダにJARを配置
3. サーバーを起動
4. `plugins/GeminiNPC/config.yml` を編集してAPIキーを設定

## APIキーの取得

1. [Google AI Studio](https://aistudio.google.com/app/apikey) にアクセス
2. APIキーを作成
3. `config.yml` の `api-key` に設定

## 使い方

### 基本フロー

```
/gemini              → システムを起動してメインメニューを表示
                     → メニューから機能を選択
/exit                → 現在のモードを終了（メニューに戻る or システム終了）
```

### メインメニュー

`/gemini` を実行すると以下のメニューが表示されます：

```
  機能を選んでください:

    /1 → 相談モード (AIに相談)
    /2 → 検索モード (Web検索)
    /3 → モデル選択 (AI切り替え)
    /4 → ヘルプ
    /5 → ステータス

    /exit → システム終了
```

### 相談モード

```
/gemini chat または /1  → 相談モード開始
/<相談内容>              → AIに相談（例: /悩みがある）
/exit                   → メインメニューに戻る
```

### Web検索モード

```
/gemini search または /2 → 検索モード開始
/<検索ワード>            → Web検索を実行（例: /Minecraft 建築 コツ）
/exit                   → メインメニューに戻る
```

### モデル選択

```
/gemini model または /3  → AIモデル選択画面を表示
/gemini model flash     → Gemini 3 Flash に切り替え
/gemini model thinking  → Gemini 3 Flash Thinking に切り替え
/gemini model pro       → Gemini 3 Pro に切り替え
```

## 利用可能なAIモデル

| モデル | コマンド | 特徴 | 思考レベル |
|--------|----------|------|-----------|
| Gemini 3 Flash | `flash` | 速度: ★★★★★ サクサク軽快な応答 | low |
| Gemini 3 Flash Thinking | `thinking` | バランス: ★★★★☆ 速度と深い思考の両立 | high |
| Gemini 3 Pro | `pro` | 品質: ★★★★★ 最高性能・詳しい回答 | high |

### モデル選択のコツ

- **Flash**: 簡単な質問、サクサク会話したい時
- **Flash Thinking**: 深く考えてほしいけど速度も欲しい時
- **Pro**: 複雑な相談、じっくり考えてほしい時

## サブコマンド一覧

| サブコマンド | ショートカット | 説明 |
|-------------|----------------|------|
| `/gemini chat` | `/1` | 相談モードを開始 |
| `/gemini search` | `/2` | 検索モードを開始 |
| `/gemini model` | `/3` | AIモデル選択 |
| `/gemini help` | `/4` | ヘルプを表示 |
| `/gemini status` | `/5` | ステータスを表示 |
| `/gemini menu` | - | メニューを再表示 |
| `/gemini exit` | `/exit` | システム終了 |
| `/gemini clear` | - | 会話履歴をクリア |

## 設定 (config.yml)

```yaml
gemini:
  api-key: "YOUR_API_KEY_HERE"  # Google AI Studio APIキー
  model: "gemini-3-flash-preview"  # デフォルトモデル
  system-prompt: |               # AIの性格設定
    あなたはMinecraftの世界で生徒の相談に乗るカウンセラーです...

npc:
  name: "相談員"                  # NPCの表示名

conversation:
  max-history: 20                # 会話履歴の最大保持数
```

## コマンド一覧

| コマンド | 説明 |
|---------|------|
| `/gemini` | システム起動・メインメニュー表示 |
| `/gemini chat` | 相談モード開始 |
| `/gemini search` | 検索モード開始 |
| `/gemini model [flash\|thinking\|pro]` | AIモデル選択・変更 |
| `/gemini help [ページ\|トピック]` | ヘルプ表示 |
| `/gemini status` | ステータス確認 |
| `/gemini clear` | 会話履歴クリア |
| `/exit` | 現在のモード終了 |
| `/geminireload` | 設定リロード (OP権限必要) |

## ヘルプシステム

### ワールド参加時
プレイヤーがサーバーに参加すると、自動でウェルカムメッセージが表示されます。

### ページ別ヘルプ
```
/gemini help      → ヘルプ1ページ目（概要）
/gemini help 2    → ヘルプ2ページ目（相談モード）
/gemini help 3    → ヘルプ3ページ目（Web検索）
/gemini help 4    → ヘルプ4ページ目（AIモデル）
```

### トピック別ヘルプ
```
/gemini help chat     → 相談モードの詳細
/gemini help search   → Web検索の詳細
/gemini help model    → AIモデルの詳細
/gemini help commands → コマンド一覧
```

## Web検索機能

Web検索機能はGemini APIのGoogle Search Grounding機能を使用しています。

### 特徴
- リアルタイムでWebから情報を取得
- 検索結果を400文字以内で簡潔にまとめて表示
- 情報源（ソース）も表示
- Minecraftチャットで読みやすい形式

### 使用例
```
（検索モード中）
/Minecraft レッドストーン回路 入門
/今日の天気 東京
/プログラミング 初心者 おすすめ
```

## 技術仕様

### API設定
- **出力トークン上限**: 65,536
- **タイムアウト**: 120秒
- **Thinking Level**: モデルに応じて `low` / `high` を自動設定

### 対応モデル
- `gemini-3-flash-preview` (Flash / Flash Thinking)
- `gemini-3-pro-preview` (Pro)

## 重要な注意事項

- **すべての機能は `/gemini` でシステムを起動してから使用できます**
- 数字ショートカット（`/1`〜`/5`）はセッション中のみ有効です
- ProモデルとFlash Thinkingは推論に時間がかかる場合があります
- AIの回答は400文字以内に制限されています

## ビルド方法

```bash
./build.sh
# または
mvn clean package
```

JARファイルは `builds/` フォルダに蓄積されます。

## 変更履歴

### v1.5.0
- Gemini 3 Flash Thinkingモデルを追加
- thinking_levelパラメータによる思考制御を実装
- 出力トークン上限を65,536に引き上げ
- タイムアウトを120秒に延長
- モデル別の思考インジケーターを追加

### v1.4.x
- Gemini 3 Flash / Pro 対応
- Web検索機能追加
- セッションベースのUXに刷新
- Markdown記号の自動除去

## ライセンス

MIT License
