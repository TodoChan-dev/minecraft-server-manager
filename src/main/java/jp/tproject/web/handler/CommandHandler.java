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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * サーバーコマンド実行リクエストを処理するハンドラ
 * 複数サーバー対応
 */
public class CommandHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);
    private final NotificationServer notificationServer;
    private final MinecraftServerManager serverManager;

    /**
     * CommandHandlerのインスタンスを作成
     *
     * @param notificationServer 通知サーバー
     * @param serverManager Minecraftサーバー管理クラス
     */
    public CommandHandler(NotificationServer notificationServer, MinecraftServerManager serverManager) {
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
            // リクエストボディを読み込む
            InputStream requestBody = exchange.getRequestBody();
            String requestBodyString = new String(requestBody.readAllBytes(), StandardCharsets.UTF_8);

            // JSONをパース
            Map<String, Object> requestData = JsonUtil.jsonToMap(requestBodyString);

            // コマンドとサーバーIDを取得
            String command = null;
            String serverId = null;

            if (requestData.containsKey("cmd")) {
                command = requestData.get("cmd").toString();
            }

            if (requestData.containsKey("serverId")) {
                serverId = requestData.get("serverId").toString();
            }

            if (command == null || command.trim().isEmpty()) {
                throw new AppException("リクエストには「cmd」フィールドが必要です", 400);
            }

            // サーバーIDが指定されていない場合はデフォルトサーバーを使用
            if (serverId == null || serverId.isEmpty()) {
                serverId = ConfigManager.getInstance().getDefaultServerConfig().getId();
            }

            // サーバー設定を確認
            if (ConfigManager.getInstance().getServerConfig(serverId) == null) {
                throw new AppException("指定されたサーバーIDは存在しません: " + serverId, 404);
            }

            // サニタイズとチェック
            command = sanitizeCommand(command);

            // コマンド実行のロジックを非同期で実行
            final String finalCommand = command;
            final String finalServerId = serverId;
            CompletableFuture.runAsync(() -> {
                try {
                    logger.info("サーバー {} でコマンドを実行します: {}", finalServerId, finalCommand);

                    // コマンド実行
                    String output = executeCommand(finalServerId, finalCommand);

                    logger.info("サーバー {} のコマンド実行が完了しました: {}", finalServerId, finalCommand);
                    notificationServer.notifyCommandExecution(finalServerId, finalCommand, true, output);
                } catch (Exception e) {
                    logger.error("サーバー {} のコマンド実行中にエラーが発生しました: {}", finalServerId, finalCommand, e);
                    notificationServer.notifyCommandExecution(finalServerId, finalCommand, false, e.getMessage());
                }
            });

            // 即座に成功レスポンスを返す
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("message", "コマンド実行リクエストを受け付けました");
            responseData.put("command", command);
            responseData.put("serverId", serverId);

            sendJsonResponse(exchange, 200, responseData);
        } catch (AppException e) {
            logger.warn("コマンド実行リクエストが不正です: {}", e.getMessage());

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "リクエストエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, e.getStatusCode(), errorData);
        } catch (Exception e) {
            logger.error("コマンド実行リクエスト処理中にエラーが発生しました", e);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
        }
    }

    /**
     * 指定されたサーバーでコマンドを実行して結果を返す
     *
     * @param serverId サーバーID
     * @param command 実行するコマンド
     * @return コマンド実行結果
     */
    private String executeCommand(String serverId, String command) {
        try {
            // サーバーが実行中か確認
            if (!serverManager.isServerRunning(serverId)) {
                throw new AppException("サーバー " + serverId + " は実行していません", 400);
            }

            // RCONを使用してMinecraftサーバーにコマンドを送信
            return serverManager.executeCommand(serverId, command);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException("コマンド実行中にエラーが発生しました: " + e.getMessage(), e);
        }
    }

    /**
     * コマンドの安全性チェックとサニタイズ
     *
     * @param command サニタイズするコマンド
     * @return サニタイズされたコマンド
     */
    private String sanitizeCommand(String command) {
        // 基本的なサニタイズ（例：コマンドインジェクション対策）
        command = command.trim();

        // 禁止コマンドチェック（例：サーバー停止、モード変更など）
        String lowerCmd = command.toLowerCase();
        String[] forbiddenCommands = {
                "stop", "shutdown", "reload", "restart", "kill", "op ", "deop ",
                "ban-ip", "pardon-ip", "save-all", "save-off", "save-on"
        };

        for (String forbidden : forbiddenCommands) {
            if (lowerCmd.equals(forbidden) || lowerCmd.startsWith(forbidden + " ")) {
                throw new AppException("このコマンドは実行が許可されていません: " + command, 403);
            }
        }

        return command;
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