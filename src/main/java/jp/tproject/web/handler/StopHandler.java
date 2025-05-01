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
 * サーバー停止リクエストを処理するハンドラ
 */
public class StopHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(StopHandler.class);
    private final NotificationServer notificationServer;
    private final MinecraftServerManager serverManager;

    /**
     * StopHandlerのインスタンスを作成
     *
     * @param notificationServer 通知サーバー
     * @param serverManager Minecraftサーバー管理クラス
     */
    public StopHandler(NotificationServer notificationServer, MinecraftServerManager serverManager) {
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
            // サーバー停止のロジックを実行
            CompletableFuture.runAsync(() -> {
                try {
                    // 非同期でサーバー停止コマンドを実行
                    logger.info("サーバー停止を開始します");
                    notificationServer.notifyServerStatus("stopping", "サーバーを停止しています...");

                    // ここに実際のマインクラフトサーバー停止ロジックを実装
                    // 例: Runtime.getRuntime().exec("systemctl stop minecraft.service");

                    // 実装例（実際のシステムに合わせて調整）
                    boolean success = executeStopCommand();

                    if (success) {
                        logger.info("サーバー停止コマンドを正常に実行しました");
                        notificationServer.notifyServerStatus("stopped", "サーバーが停止しました");
                    } else {
                        logger.error("サーバー停止コマンドの実行に失敗しました");
                        notificationServer.notifyServerStatus("error", "サーバー停止に失敗しました");
                    }
                } catch (Exception e) {
                    logger.error("サーバー停止中にエラーが発生しました", e);
                    notificationServer.notifyServerStatus("error", "サーバー停止中にエラー: " + e.getMessage());
                }
            });

            // 即座に成功レスポンスを返す
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("message", "サーバー停止コマンドを受け付けました");

            sendJsonResponse(exchange, 200, responseData);
        } catch (Exception e) {
            logger.error("停止リクエスト処理中にエラーが発生しました", e);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
        }
    }

    /**
     * 実際のサーバー停止コマンドを実行
     *
     * @return 成功時true
     */
    private boolean executeStopCommand() {
        try {
            // ここに実際の停止コマンドの実装
            // 例: シェルスクリプトを実行する
            // ProcessBuilder pb = new ProcessBuilder("/path/to/stop_script.sh");
            // Process process = pb.start();
            // return process.waitFor() == 0;

            // 開発用のモック実装（常に成功）
            Thread.sleep(2000); // 処理に2秒かかると仮定
            return true;
        } catch (Exception e) {
            logger.error("停止コマンド実行中にエラーが発生しました", e);
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