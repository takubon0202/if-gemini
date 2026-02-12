/**
 * if-Gemini スプレッドシート連携 GAS
 *
 * ===== 設定手順 =====
 *
 * 1. Google スプレッドシートを新規作成（または既存のを使用）
 * 2. スプレッドシートで「拡張機能 > Apps Script」を開く
 * 3. このコードを全て貼り付け
 * 4. 上のメニューから「setup」関数を選択して ▶ 実行
 *    → 権限の承認ダイアログが出るので「許可」をクリック
 *    → 「セットアップ完了！」と表示されればOK
 * 5. デプロイ > 新しいデプロイ > ウェブアプリ
 *    - 実行するユーザー: 自分
 *    - アクセスできるユーザー: 全員
 * 6. デプロイURLをconfig.ymlのgas-urlに設定
 *
 * ※ setup() を実行すると権限が認可され、
 *   スプレッドシートIDも自動保存されます
 */

/**
 * 初期設定（スクリプトエディタで1回実行してください）
 * → スプレッドシートへのアクセス権限を取得
 * → スプレッドシートIDを自動保存
 */
function setup() {
  // スプレッドシートにアクセスして権限を取得
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    SpreadsheetApp.getUi().alert(
      'エラー: スプレッドシートから Apps Script を開いてください。\n' +
      '（スプレッドシート > 拡張機能 > Apps Script）'
    );
    return;
  }

  // スプレッドシートIDをスクリプトプロパティに保存
  PropertiesService.getScriptProperties().setProperty('SPREADSHEET_ID', ss.getId());

  // 「会話ログ」シートを準備
  let sheet = ss.getSheetByName('会話ログ');
  if (!sheet) {
    sheet = ss.insertSheet('会話ログ');
    sheet.appendRow([
      'タイムスタンプ', 'プレイヤー名', 'UUID',
      'モード', 'モデル', 'ユーザー入力', 'AI応答', 'サーバー'
    ]);
    sheet.getRange(1, 1, 1, 8).setFontWeight('bold');
    sheet.setFrozenRows(1);
  }

  SpreadsheetApp.getUi().alert(
    'セットアップ完了！\n\n' +
    'スプレッドシートID: ' + ss.getId() + '\n\n' +
    '次のステップ:\n' +
    '1. デプロイ > 新しいデプロイ > ウェブアプリ\n' +
    '2. 実行するユーザー: 自分\n' +
    '3. アクセスできるユーザー: 全員\n' +
    '4. デプロイURLをconfig.ymlのgas-urlに設定'
  );
}

/**
 * スプレッドシートを取得（保存済みIDを使用）
 */
function getSheet() {
  // 1. スクリプトプロパティから保存済みIDを取得
  const savedId = PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID');
  if (savedId) {
    return SpreadsheetApp.openById(savedId);
  }

  // 2. コンテナバインドの場合
  try {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    if (ss) {
      // 次回のためにIDを保存
      PropertiesService.getScriptProperties().setProperty('SPREADSHEET_ID', ss.getId());
      return ss;
    }
  } catch (e) {}

  throw new Error(
    'セットアップが必要です。Apps Scriptエディタで setup() を実行してください。'
  );
}

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    const ss = getSheet();

    let sheet = ss.getSheetByName('会話ログ');
    if (!sheet) {
      sheet = ss.insertSheet('会話ログ');
      sheet.appendRow([
        'タイムスタンプ', 'プレイヤー名', 'UUID',
        'モード', 'モデル', 'ユーザー入力', 'AI応答', 'サーバー'
      ]);
      sheet.getRange(1, 1, 1, 8).setFontWeight('bold');
      sheet.setFrozenRows(1);
    }

    sheet.appendRow([
      new Date().toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' }),
      data.playerName || '',
      data.uuid || '',
      data.mode || '',
      data.model || '',
      data.userInput || '',
      data.aiResponse || '',
      data.server || ''
    ]);

    return ContentService.createTextOutput(
      JSON.stringify({ status: 'ok' })
    ).setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(
      JSON.stringify({ status: 'error', message: error.toString() })
    ).setMimeType(ContentService.MimeType.JSON);
  }
}

function doGet(e) {
  try {
    const params = e.parameter || {};

    if (params.uuid) {
      const uuid = params.uuid;
      const limit = parseInt(params.limit) || 20;
      const ss = getSheet();
      const sheet = ss.getSheetByName('会話ログ');

      if (!sheet) {
        return ContentService.createTextOutput(
          JSON.stringify({ status: 'ok', entries: [] })
        ).setMimeType(ContentService.MimeType.JSON);
      }

      const data = sheet.getDataRange().getValues();
      const entries = [];
      for (let i = data.length - 1; i >= 1; i--) {
        if (data[i][2] === uuid) {
          entries.push({
            timestamp: data[i][0],
            playerName: data[i][1],
            mode: data[i][3],
            model: data[i][4],
            userInput: data[i][5],
            aiResponse: data[i][6],
            server: data[i][7]
          });
          if (entries.length >= limit) break;
        }
      }

      return ContentService.createTextOutput(
        JSON.stringify({ status: 'ok', entries: entries })
      ).setMimeType(ContentService.MimeType.JSON);
    }

    return ContentService.createTextOutput(
      JSON.stringify({ status: 'ok', message: 'if-Gemini GAS endpoint is active' })
    ).setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(
      JSON.stringify({ status: 'error', message: error.toString() })
    ).setMimeType(ContentService.MimeType.JSON);
  }
}
