# if-Gemini v2.1.4 - Minecraft AI 多機能プラグイン

MinecraftサーバーでGoogle Gemini AIを使った**相談・Web検索・コマンド生成・画像生成**を提供するSpigotプラグインです。

## 主な機能

| 機能 | 説明 |
|------|------|
| **相談モード** | AIカウンセラーに気軽に相談。会話履歴を保持し文脈を理解 |
| **検索モード** | リアルタイムWeb検索。結果をAIが要約して表示 |
| **コマンド生成** | 自然言語からMinecraft 1.21.11対応コマンドを生成。クリックで実行 |
| **画像生成** | AIで画像を生成。Image-to-Image変換にも対応 |
| **ライブラリ** | 生成した画像の履歴を保存・閲覧・再利用 |

## 動作環境

- Minecraft Java Edition 1.21+
- Spigot / Paper サーバー
- Java 21+
- Google AI Studio APIキー

## インストール

1. [Releases](https://github.com/takubon0202/if-gemini/releases) または `builds/` からJARファイルを取得
2. サーバーの `plugins` フォルダにJARを配置
3. サーバーを起動
4. `plugins/GeminiNPC/config.yml` を編集してAPIキーを設定
5. `/geminireload` で設定を反映

## APIキーの取得

1. [Google AI Studio](https://aistudio.google.com/app/apikey) にアクセス
2. APIキーを作成
3. `config.yml` の `gemini.api-key` に設定

---

## 使い方

### 基本フロー

```
/gemini  →  メインメニュー表示
         →  メニューからモードを選択（クリック or 番号入力）
         →  チャットで内容を入力（/ 不要）
         →  [メニューに戻る] ボタンで戻る
```

### メインメニュー

`/gemini` を実行するとクリック可能なメニューが表示されます：

```
  [1] 相談モード     AIに相談
  [2] 検索モード     Web検索
  [3] コマンド生成   AIでコマンド作成
  [4] 画像生成モード AI画像生成
  [5] ヘルプ
  [6] ステータス
  [7] ライブラリ     過去の画像

  [終了]
```

メニューのボタンをクリックするか、チャットで番号（`1`〜`7`）やキーワード（`chat`, `search` 等）を入力して選択します。

---

### 相談モード

AIカウンセラーが悩みや相談に応えます。会話履歴を保持し、文脈を理解した対話が可能です。

```
開始: メニューで [1] をクリック or /gemini chat
入力: チャットで相談内容を入力（/ 不要）
例:   悩みがある
例:   勉強のやる気が出ない
```

応答後に `[モデル変更]` `[履歴クリア]` `[メニューに戻る]` ボタンが表示されます。

### 検索モード

リアルタイムでWebから情報を検索し、AIがわかりやすくまとめます。

```
開始: メニューで [2] をクリック or /gemini search
入力: チャットで検索ワードを入力（/ 不要）
例:   Minecraft 建築 コツ
例:   レッドストーン回路 初心者
```

### コマンド生成モード

自然言語（日本語・ローマ字対応）からMinecraft 1.21.11対応のコマンドを生成します。
生成されたコマンドはクリックで即実行、またはチャットバーにコピーできます。

```
開始: メニューで [3] をクリック or /gemini command
入力: チャットでやりたいことを入力（/ 不要）
例:   最強の剣を作って
例:   ダイヤの装備を全部ください
例:   saikyo no ken wo tukutte（ローマ字OK）
```

生成結果の例：

```
  コマンド:
  /give @p diamond_sword[custom_name='{"text":"最強の剣","italic":false}',
    unbreakable={},enchantments={"sharpness":255,"fire_aspect":255,...}] 1

  [実行]  [コピー]

  説明: ダイヤの剣に全エンチャントLv255を付与し...
```

### 画像生成モード

AIで画像を生成します。Image-to-Image変換にも対応しています。

```
開始: メニューで [4] をクリック or /gemini image
入力: チャットで画像の説明を入力（/ 不要）
例:   夕焼けの海
例:   かわいい猫がMinecraftで遊んでいる
例:   yuuyakenoumi（ローマ字OK）
```

#### Image-to-Image

既存の画像URLとプロンプトを組み合わせて画像を変換できます。

```
入力: https://example.com/image.png アニメ風にして
```

#### 画像設定

モード内のボタンまたはコマンドで設定変更：

| 設定 | コマンド | 説明 |
|------|---------|------|
| 設定一覧 | `/gemini image settings` | 全設定をまとめて表示 |
| モデル | `/gemini imagemodel` | Nanobanana / Nanobanana Pro |
| アスペクト比 | `/gemini image ratio <値>` | 1:1, 16:9, 9:16 等 |
| 解像度 | `/gemini image resolution <値>` | 1K / 2K / 4K (Proのみ) |

### ライブラリ

生成した画像の履歴を閲覧・再利用できます。

```
開始: メニューで [7] をクリック or /gemini library
操作: [表示] でブラウザで画像を開く
      [変換] でImage-to-Image変換を開始
```

---

## AIモデル

### テキストモデル（相談・検索・コマンド生成）

| モデル | 特徴 | おすすめ用途 |
|--------|------|-------------|
| **Gemini 3 Flash** | 速度: ★★★★★ | 簡単な質問、サクサク会話 |
| **Gemini 3 Flash Thinking** | バランス: ★★★★☆ | 深く考えてほしいけど速度も欲しい時 |
| **Gemini 3 Pro** | 品質: ★★★★★ | 複雑な相談、コマンド生成 |

変更方法：各モード内の `[モデル変更]` ボタン、または `/gemini model [flash|thinking|pro]`

### 画像モデル

| モデル | 特徴 | 解像度 | コスト目安 |
|--------|------|--------|-----------|
| **Nanobanana** (Flash) | 高速・軽量 | 1Kのみ | ~$0.04/枚 |
| **Nanobanana Pro** | 高品質 | 1K / 2K / 4K | ~$0.13-0.24/枚 |

変更方法：画像モード内の `[モデル変更]` ボタン、または `/gemini imagemodel [nanobanana|nanobanana-pro]`

---

## コマンド一覧

### メインコマンド

| コマンド | 説明 |
|---------|------|
| `/gemini` | システム起動・メインメニュー表示 |
| `/gemini chat` | 相談モード開始 |
| `/gemini search` | 検索モード開始 |
| `/gemini command` | コマンド生成モード開始 |
| `/gemini image` | 画像生成モード開始 |
| `/gemini model [flash\|thinking\|pro]` | テキストモデル選択・変更 |
| `/gemini imagemodel [nanobanana\|nanobanana-pro]` | 画像モデル選択・変更 |
| `/gemini image settings` | 画像設定メニュー表示 |
| `/gemini image ratio <値>` | アスペクト比変更 |
| `/gemini image resolution <値>` | 解像度変更 (Proのみ) |
| `/gemini help [ページ\|トピック]` | ヘルプ表示 |
| `/gemini status` | ステータス確認 |
| `/gemini library` | 画像ライブラリ表示 |
| `/gemini clear` | 会話履歴クリア |
| `/gemini menu` | メインメニューに戻る |
| `/gemini exit` | システム終了 |
| `/exit` | 現在のモード終了 / メニューに戻る |
| `/geminireload` | 設定リロード (OP権限) |

### エイリアス

| エイリアス | 本体コマンド |
|-----------|-------------|
| `/ai`, `/counsel` | `/gemini` |
| `/ws`, `/search` | `/websearch` |
| `/gmodel` | `/geminimodel` |
| `/ghelp` | `/geminihelp` |
| `/gimage`, `/img` | `/geminiimage` |
| `/quit`, `/end` | `/exit` |

---

## 設定 (config.yml)

```yaml
# Gemini API設定
gemini:
  api-key: "YOUR_API_KEY_HERE"       # Google AI Studio APIキー
  model: "gemini-3-flash-preview"     # デフォルトテキストモデル
  system-prompt: |                    # AIの性格設定（カスタマイズ可能）
    あなたはMinecraftの世界で生徒の相談に乗るカウンセラーです...

# NPC設定
npc:
  name: "相談員"                       # 表示名

# 会話設定
conversation:
  max-history: 20                     # 会話履歴の最大保持数

# 画像生成設定
image:
  hosting: "catbox"                   # ホスティング (catbox / imgbb)
  imgbb-api-key: "YOUR_KEY"          # imgbb使用時のみ必要
  default-model: "gemini-2.5-flash-image"  # デフォルト画像モデル
  aspect-ratio: "1:1"                # デフォルトアスペクト比
  default-resolution: "1K"           # デフォルト解像度

# ライブラリ設定
library:
  max-history: 50                    # 1プレイヤーあたりの最大保存数

# コマンド生成設定
command:
  # model: "gemini-3-pro-preview"    # 専用モデル（未設定時はプレイヤー選択モデル使用）
```

### 画像ホスティング

| サービス | APIキー | 特徴 |
|---------|---------|------|
| **Catbox** (デフォルト) | 不要 | 無料・設定不要 |
| **ImgBB** | 必要 | 安定・高速。[api.imgbb.com](https://api.imgbb.com/) で取得 |

---

## 権限

| 権限ノード | デフォルト | 説明 |
|-----------|----------|------|
| `gemininpc.use` | `true` | チャット機能の使用 |
| `gemininpc.websearch` | `true` | Web検索機能の使用 |
| `gemininpc.image` | `true` | 画像生成機能の使用 |
| `gemininpc.reload` | `op` | 設定リロード |

---

## ヘルプシステム

### ワールド参加時
プレイヤーがサーバーに参加すると、自動でウェルカムメッセージが表示されます。

### ページ別ヘルプ
```
/gemini help      → 概要
/gemini help 2    → 相談モード
/gemini help 3    → 検索モード
/gemini help 4    → AIモデル
/gemini help 5    → 画像生成
```

### トピック別ヘルプ
```
/gemini help chat      → 相談モードの詳細
/gemini help search    → Web検索の詳細
/gemini help command   → コマンド生成の詳細
/gemini help image     → 画像生成の詳細
/gemini help model     → AIモデルの詳細
/gemini help commands  → コマンド一覧
```

---

## 技術仕様

### API設定
- **出力トークン上限**: 65,536
- **タイムアウト**: 120秒
- **Thinking Level**: モデルに応じて `low` / `high` を自動設定

### 対応モデル
| 用途 | モデルID |
|------|---------|
| テキスト (Flash) | `gemini-3-flash-preview` |
| テキスト (Flash Thinking) | `gemini-3-flash-thinking` |
| テキスト (Pro) | `gemini-3-pro-preview` |
| 画像 (Nanobanana) | `gemini-2.5-flash-image` |
| 画像 (Nanobanana Pro) | `gemini-3-pro-image-preview` |

### コマンド生成
- Minecraft Java Edition 1.21.11 Component形式に完全対応
- snake_case ID強制（`diamond_sword`, `fire_aspect` 等）
- JSON text component形式のcustom_name対応
- ローマ字入力を日本語に自動変換

### 画像生成
- Catbox / ImgBB へのアップロードに対応
- 10種類のアスペクト比（1:1, 16:9, 9:16 等）
- 最大4K解像度（Nanobanana Pro）
- Image-to-Image変換対応

---

## ビルド方法

```bash
mvn clean package
```

JARファイルは `target/` に生成され、`builds/` フォルダにもコピーされます。

---

## 変更履歴

### v2.1.4
- enchantments構文をMC 1.21.5+フラット形式に修正（`levels:` キー廃止対応）
- [実行]ボタンがAI対話にならないようMCバニラコマンドをホワイトリスト許可
- `fixCommandSyntax`で`levels:`ラッパーを自動除去するよう修正

### v2.1.3
- AI生成コマンドの自動構文修正機能を追加（約80パターンのsnake_case自動変換）
- アイテムID・エンチャントID・エフェクトID・コンポーネント名を後処理で確実に修正

### v2.1.2
- プラグイン表示名を「GeminiNPC」から「if-Gemini」にリブランド
- enchantments構文をMC 1.21〜1.21.4対応に修正（`levels:` キー必須）
- SNBTキーのダブルクォート除去（標準SNBT形式に準拠）
- システムプロンプトに全身装備の完全な例を追加

### v2.1.1
- チャットベース入力に移行 - `/` プレフィックス不要で直接入力可能に
- 全ボタンを登録済みコマンド(`/gemini`)に統一し、赤文字・確認ダイアログを解消
- 全モードの応答後にナビゲーションフッター表示（[モデル変更] [履歴クリア] [メニューに戻る]等）
- MC 1.21.11コマンド構文を修正（snake_case強制、JSON text component形式のcustom_name対応）
- README.mdを現行仕様に全面更新

### v2.1.0
- **コマンド生成モード**を追加 - 自然言語からMCコマンドを生成
- MC 1.21.11 Component形式に完全対応したシステムプロンプト
- メニュー順序を変更: 1:相談 → 2:検索 → 3:コマンド → 4:画像
- 全モードにモデル変更機能を追加（[モデル変更]ボタン）

### v2.0.1
- Image-to-Image画像ダウンロードの修正
- ローマ字入力サポートの追加
- UX改善

### v2.0.0
- **画像生成モード**を追加（Nanobanana / Nanobanana Pro）
- **ライブラリ機能**を追加（過去の画像を保存・閲覧）
- **Image-to-Image変換**を追加
- モデル名表示の改善

### v1.8.0
- 画像アップロードのCloudflareバイパス修正
- Litterboxフォールバック追加

### v1.5.0
- Gemini 3 Flash Thinkingモデルを追加
- thinking_levelパラメータによる思考制御を実装
- 出力トークン上限を65,536に引き上げ

### v1.4.x
- Gemini 3 Flash / Pro 対応
- Web検索機能追加
- セッションベースのUXに刷新

---

## ライセンス

MIT License
