package jp.tproject.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jp.tproject.core.JsonUtil;
import jp.tproject.minecraft.MinecraftServerManager;
import jp.tproject.ws.NotificationServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * サーバー再起動リクエストを処理するハンドラ
 */
public class RestartHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(RestartHandler.class);
    private final NotificationServer notificationServer;
    private final MinecraftServerManager serverManager;

    /**
     * RestartHandlerのインスタンスを作成
     *
     * @param notificationServer 通知サーバー
     * @param serverManager Minecraftサーバー管理クラス
     */
    public RestartHandler(NotificationServer notificationServer, MinecraftServerManager serverManager) {
        this.notificationServer = notificationServer;
        this.serverManager = serverManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // POSTリクエストのみを許可
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        // CORSヘッダーを設定
        setCorsHeaders(exchange);

        try {
            // サーバー再起動のロジックを実行
            CompletableFuture.runAsync(() -> {
                try {
                    // 非同期でサーバー再起動コマンドを実行
                    logger.info("サーバー再起動を開始します");
                    notificationServer.notifyServerStatus("restarting", "サーバーを再起動しています...");

                    // ここに実際のマインクラフトサーバー再起動ロジックを実装
                    // 例: Runtime.getRuntime().exec("systemctl restart minecraft.service");

                    // 実装例（実際のシステムに合わせて調整）
                    boolean success = executeRestartCommand();

                    if (success) {
                        logger.info("サーバー再起動コマンドを正常に実行しました");
                        notificationServer.notifyServerStatus("starting", "サーバーが再起動中です");
                    } else {
                        logger.error("サーバー再起動コマンドの実行に失敗しました");
                        notificationServer.notifyServerStatus("error", "サーバー再起動に失敗しました");
                    }
                } catch (Exception e) {
                    logger.error("サーバー再起動中にエラーが発生しました", e);
                    notificationServer.notifyServerStatus("error", "サーバー再起動中にエラー: " + e.getMessage());
                }
            });

            // 即座に成功レスポンスを返す
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("message", "サーバー再起動コマンドを受け付けました");

            sendJsonResponse(exchange, 200, responseData);
        } catch (Exception e) {
            logger.error("再起動リクエスト処理中にエラーが発生しました", e);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
        }
    }

    /**
     * 実際のサーバー再起動コマンドを実行
     *
     * @return 成功時true
     */
    private boolean executeRestartCommand() {
        try {
            // ここに実際の再起動コマンドの実装
            // 例: シェルスクリプトを実行する
            // ProcessBuilder pb = new ProcessBuilder("/path/to/restart_script.sh");
            // Process process = pb.start();
            // return process.waitFor() == 0;

            // 開発用のモック実装（常に成功）
            Thread.sleep(2000); // 処理に2秒かかると仮定
            return true;
        } catch (Exception e) {
            logger.error("再起動コマンド実行中にエラーが発生しました", e);
            return false;
        }
    }

    /**
     * JSONレスポンスを送信
     *
     * @param exchange HTTPExchange
     * @param statusCode HTTPステータスコード
     * @param data レスポンスデータ
     * @throws IOException 入出力例外
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) throws IOException {
        String response = JsonUtil.toJson(data);
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * 405 Method Not Allowed レスポンスを送信
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("success", false);
        errorData.put("error", "Method Not Allowed");
        errorData.put("message", "POSTメソッドのみが許可されています");

        setCorsHeaders(exchange);
        sendJsonResponse(exchange, 405, errorData);
    }

    /**
     * CORSヘッダーを設定
     *
     * @param exchange HTTPExchange
     */
    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }
}