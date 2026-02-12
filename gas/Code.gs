/**
 * if-Gemini スプレッドシート連携 GAS
 *
 * 使い方:
 * 1. Google スプレッドシートを新規作成
 * 2. 拡張機能 > Apps Script を開く
 * 3. このコードを貼り付け
 * 4. SPREADSHEET_ID を自分のスプレッドシートのIDに変更
 * 5. デプロイ > 新しいデプロイ > ウェブアプリ
 *    - 実行するユーザー: 自分
 *    - アクセスできるユーザー: 全員
 * 6. デプロイして表示されるURLをconfig.ymlのgas-urlに設定
 */

// スプレッドシートIDを設定してください
const SPREADSHEET_ID = 'YOUR_SPREADSHEET_ID_HERE';

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    const ss = SpreadsheetApp.openById(SPREADSHEET_ID);

    // シート名は "会話ログ"
    let sheet = ss.getSheetByName('会話ログ');
    if (!sheet) {
      sheet = ss.insertSheet('会話ログ');
      // ヘッダー行を追加
      sheet.appendRow([
        'タイムスタンプ',
        'プレイヤー名',
        'UUID',
        'モード',
        'モデル',
        'ユーザー入力',
        'AI応答',
        'サーバー'
      ]);
      // ヘッダーを太字に
      sheet.getRange(1, 1, 1, 8).setFontWeight('bold');
      sheet.setFrozenRows(1);
    }

    // データを追記
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

    // UUIDが指定された場合、そのプレイヤーの履歴を返す
    if (params.uuid) {
      const uuid = params.uuid;
      const limit = parseInt(params.limit) || 20;
      const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
      const sheet = ss.getSheetByName('会話ログ');

      if (!sheet) {
        return ContentService.createTextOutput(
          JSON.stringify({ status: 'ok', entries: [] })
        ).setMimeType(ContentService.MimeType.JSON);
      }

      const data = sheet.getDataRange().getValues();
      // ヘッダー行をスキップ(1行目)、UUIDでフィルタ
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

    // パラメータなしの場合はステータス確認
    return ContentService.createTextOutput(
      JSON.stringify({ status: 'ok', message: 'if-Gemini GAS endpoint is active' })
    ).setMimeType(ContentService.MimeType.JSON);

  } catch (error) {
    return ContentService.createTextOutput(
      JSON.stringify({ status: 'error', message: error.toString() })
    ).setMimeType(ContentService.MimeType.JSON);
  }
}
