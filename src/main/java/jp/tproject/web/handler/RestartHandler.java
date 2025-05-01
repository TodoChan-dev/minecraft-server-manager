package jp.tproject.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jp.tproject.config.ConfigManager;
import jp.tproject.core.AppException;
import jp.tproject.core.JsonUtil;
import jp.tproject.minecraft.MinecraftServerManager;
import jp.tproject.ws.NotificationServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * サーバー再起動リクエストを処理するハンドラ
 * 複数サーバー対応
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
        // OPTIONSリクエストの場合はCORSプリフライトリクエストとして処理
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            handleCorsPreflightRequest(exchange);
            return;
        }

        // POSTリクエストのみを許可
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        // CORSヘッダーを設定
        setCorsHeaders(exchange);

        try {
            // リクエストボディを読み込み
            String requestBody = "";
            String serverId = null;
            boolean restartAll = false;

            // リクエストボディがある場合は読み込む
            if (exchange.getRequestBody().available() > 0) {
                InputStream requestBodyStream = exchange.getRequestBody();
                requestBody = new String(requestBodyStream.readAllBytes(), StandardCharsets.UTF_8);

                // JSONをパース
                if (!requestBody.isEmpty()) {
                    Map<String, Object> requestData = JsonUtil.jsonToMap(requestBody);
                    serverId = (String) requestData.get("serverId");

                    // すべてのサーバーを再起動するかどうか
                    if (requestData.containsKey("all")) {
                        restartAll = Boolean.parseBoolean(requestData.get("all").toString());
                    }
                }
            }

            // allパラメータが指定されていれば全サーバーを再起動
            if (restartAll) {
                handleRestartAllServers(exchange);
                return;
            }

            // サーバーIDが指定されていない場合はデフォルトサーバーを使用
            if (serverId == null || serverId.isEmpty()) {
                serverId = ConfigManager.getInstance().getDefaultServerConfig().getId();
            }

            // サーバー設定を確認
            if (ConfigManager.getInstance().getServerConfig(serverId) == null) {
                throw new AppException("指定されたサーバーIDは存在しません: " + serverId, 404);
            }

            // ここで非同期にサーバー再起動を実行
            final String finalServerId = serverId;
            CompletableFuture.runAsync(() -> {
                try {
                    logger.info("サーバー {} の再起動を開始します", finalServerId);
                    notificationServer.notifyServerStatus(finalServerId, "restarting", "サーバーを再起動しています...");

                    boolean success = serverManager.restartServer(finalServerId);

                    if (success) {
                        logger.info("サーバー {} の再起動コマンドを正常に実行しました", finalServerId);
                        notificationServer.notifyServerStatus(finalServerId, "starting", "サーバーが再起動中です");
                    } else {
                        logger.error("サーバー {} の再起動コマンドの実行に失敗しました", finalServerId);
                        notificationServer.notifyServerStatus(finalServerId, "error", "サーバー再起動に失敗しました");
                    }
                } catch (Exception e) {
                    logger.error("サーバー {} の再起動中にエラーが発生しました", finalServerId, e);
                    notificationServer.notifyServerStatus(finalServerId, "error",
                            "サーバー再起動中にエラー: " + e.getMessage());
                }
            });

            // 即座に成功レスポンスを返す
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("message", "サーバー " + serverId + " の再起動コマンドを受け付けました");
            responseData.put("serverId", serverId);

            sendJsonResponse(exchange, 200, responseData);
        } catch (AppException e) {
            logger.warn("再起動リクエストが不正です: {}", e.getMessage());

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "リクエストエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, e.getStatusCode(), errorData);
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
     * すべてのサーバーを再起動
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void handleRestartAllServers(HttpExchange exchange) throws IOException {
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("全サーバーの再起動を開始します");
                notificationServer.notifyGlobalStatus("restarting_all", "すべてのサーバーを再起動しています...");

                List<String> successServers = serverManager.restartAllServers();

                logger.info("全サーバー再起動処理が完了しました。成功: {}", successServers.size());
                notificationServer.notifyGlobalStatus("starting_all",
                        "すべてのサーバーが再起動中です。成功: " + successServers.size());

                // 各サーバーの状態を通知
                Map<String, String> allStatus = serverManager.getAllServerStatus();
                for (Map.Entry<String, String> entry : allStatus.entrySet()) {
                    notificationServer.notifyServerStatus(entry.getKey(), entry.getValue(),
                            "サーバー " + entry.getKey() + " は " + entry.getValue() + " 状態です");
                }
            } catch (Exception e) {
                logger.error("全サーバー再起動中にエラーが発生しました", e);
                notificationServer.notifyGlobalStatus("error", "全サーバー再起動中にエラー: " + e.getMessage());
            }
        });

        // 即座に成功レスポンスを返す
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("message", "すべてのサーバーの再起動コマンドを受け付けました");
        responseData.put("allServers", true);

        sendJsonResponse(exchange, 200, responseData);
    }

    /**
     * CORSプリフライトリクエストを処理
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void handleCorsPreflightRequest(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1); // 204 No Content
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