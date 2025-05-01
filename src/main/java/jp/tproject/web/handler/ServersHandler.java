package jp.tproject.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jp.tproject.config.ConfigManager;
import jp.tproject.config.ServerConfig;
import jp.tproject.core.JsonUtil;
import jp.tproject.minecraft.MinecraftServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * サーバー一覧と情報を提供するハンドラー
 */
public class ServersHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServersHandler.class);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORSヘッダーを設定
        setCorsHeaders(exchange);

        // OPTIONSリクエストの場合はCORSプリフライトリクエストとして処理
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            handleCorsPreflightRequest(exchange);
            return;
        }

        // GETリクエストのみを許可
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        try {
            // 全サーバー情報を取得
            List<Map<String, Object>> serversInfo = getServersInfo();

            // レスポンスデータを作成
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("servers", serversInfo);

            // デフォルトサーバーIDも含める
            String defaultServerId = ConfigManager.getInstance().getDefaultServerConfig().getId();
            responseData.put("defaultServerId", defaultServerId);

            sendJsonResponse(exchange, 200, responseData);
        } catch (Exception e) {
            logger.error("サーバー情報の取得中にエラーが発生しました", e);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
        }
    }

    /**
     * すべてのサーバー情報を取得
     *
     * @return サーバー情報のリスト
     */
    private List<Map<String, Object>> getServersInfo() {
        List<Map<String, Object>> result = new ArrayList<>();

        ConfigManager configManager = ConfigManager.getInstance();
        MinecraftServerManager serverManager = MinecraftServerManager.getInstance();

        // 現在のサーバー状態を更新
        Map<String, String> allServerStatus = serverManager.getAllServerStatus();

        // すべてのサーバー設定を取得してレスポンス用データに変換
        for (ServerConfig config : configManager.getAllServerConfigs().values()) {
            Map<String, Object> serverInfo = new HashMap<>();

            String serverId = config.getId();

            serverInfo.put("id", serverId);
            serverInfo.put("name", config.getName());
            serverInfo.put("rconHost", config.getRconHost());
            serverInfo.put("rconPort", config.getRconPort());
            // パスワードは送信しない
            serverInfo.put("status", allServerStatus.getOrDefault(serverId, "unknown"));
            serverInfo.put("pluginsDirectory", config.getPluginsDirectory());

            // 追加パラメータがあれば含める（機密情報を除く）
            Map<String, String> extraParams = new HashMap<>();
            for (Map.Entry<String, String> entry : config.getExtraParams().entrySet()) {
                String key = entry.getKey();
                if (!key.toLowerCase().contains("password") &&
                        !key.toLowerCase().contains("secret") &&
                        !key.toLowerCase().contains("token")) {
                    extraParams.put(key, entry.getValue());
                }
            }

            if (!extraParams.isEmpty()) {
                serverInfo.put("extraParams", extraParams);
            }

            result.add(serverInfo);
        }

        return result;
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
        errorData.put("message", "GETメソッドのみが許可されています");

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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }
}