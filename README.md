# GeminiNPC - Minecraft AI相談システム

MinecraftサーバーでGoogle Gemini AIを使った相談システムを提供するSpigotプラグインです。

## 機能

- `/gemini` コマンドで相談モードを開始
- 相談モード中は `/<相談内容>` で直接AIに相談可能
- カウンセリングマインドな回答スタイル（共感→要約→アクション提案）
- 会話履歴を保持し、文脈を理解した対話が可能

## 動作環境

- Minecraft 1.21.1+
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

```
/gemini              → 相談モード開始
/<相談内容>           → AIに相談（例: /何をすればいいかわからない）
/exit                → 相談モード終了
```

### 相談モード中の動作

- すべてのコマンドが相談メッセージとして扱われます
- `/exit` のみが相談モード終了コマンドとして機能します
- 他のMinecraftコマンドは相談モード終了後に使用できます

## 設定 (config.yml)

```yaml
gemini:
  api-key: "YOUR_API_KEY_HERE"  # Google AI Studio APIキー
  model: "gemini-2.0-flash"      # 使用するモデル
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
| `/gemini` | 相談モードを開始 |
| `/exit` | 相談モードを終了 |
| `/geminiclear` | 会話履歴をクリア |
| `/geminireload` | 設定をリロード (OP権限必要) |
| `/geminihelp` | ヘルプを表示 |

## ビルド方法

```bash
mvn clean package
```

JARファイルは `target/GeminiNPC-1.0.0.jar` に生成されます。

## ライセンス

MIT License
