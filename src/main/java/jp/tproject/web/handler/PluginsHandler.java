package jp.tproject.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * プラグイン管理リクエストを処理するハンドラ
 */
public class PluginsHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(PluginsHandler.class);
    private final NotificationServer notificationServer;
    private final MinecraftServerManager serverManager;

    // プラグインディレクトリのパス
    private static final String PLUGINS_DIR = System.getenv("MINECRAFT_PLUGINS_DIR") != null
            ? System.getenv("MINECRAFT_PLUGINS_DIR")
            : "./plugins";

    // 無効化されたプラグインの拡張子
    private static final String DISABLED_EXTENSION = ".disabled";

    /**
     * PluginsHandlerのインスタンスを作成
     *
     * @param notificationServer 通知サーバー
     * @param serverManager Minecraftサーバー管理クラス
     */
    public PluginsHandler(NotificationServer notificationServer, MinecraftServerManager serverManager) {
        this.notificationServer = notificationServer;
        this.serverManager = serverManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORSヘッダーを設定
        setCorsHeaders(exchange);

        // OPTIONSリクエストの場合はCORSプリフライトリクエストとして処理
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            handleCorsPreflightRequest(exchange);
            return;
        }

        // リクエストパスを取得
        String path = exchange.getRequestURI().getPath();

        try {
            // プラグイン一覧取得 (GET /plugins)
            if (exchange.getRequestMethod().equalsIgnoreCase("GET") && path.equals("/plugins")) {
                handleGetPlugins(exchange);
                return;
            }

            // プラグイン有効/無効切替 (POST /plugins/{name}/toggle)
            if (exchange.getRequestMethod().equalsIgnoreCase("POST") && path.matches("/plugins/[^/]+/toggle")) {
                handleTogglePlugin(exchange);
                return;
            }

            // 未対応のエンドポイント
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "Not Found");
            errorData.put("message", "要求されたエンドポイントは存在しません");

            sendJsonResponse(exchange, 404, errorData);
        } catch (AppException e) {
            logger.warn("プラグイン管理リクエストが不正です: {}", e.getMessage());

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "リクエストエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, e.getStatusCode(), errorData);
        } catch (Exception e) {
            logger.error("プラグイン管理リクエスト処理中にエラーが発生しました", e);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
        }
    }

    /**
     * プラグイン一覧を取得
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void handleGetPlugins(HttpExchange exchange) throws IOException {
        try {
            // プラグインディレクトリを取得
            Path pluginsDir = Paths.get(PLUGINS_DIR);
            if (!Files.exists(pluginsDir) || !Files.isDirectory(pluginsDir)) {
                throw new AppException("プラグインディレクトリが見つかりません: " + PLUGINS_DIR, 500);
            }

            // プラグイン一覧を取得
            List<Map<String, Object>> plugins = new ArrayList<>();

            // JAR拡張子のファイルとDISABLED_EXTENSION拡張子のファイルを取得
            List<Path> pluginFiles = Files.list(pluginsDir)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".jar") || fileName.endsWith(DISABLED_EXTENSION);
                    })
                    .collect(Collectors.toList());

            for (Path pluginFile : pluginFiles) {
                String fileName = pluginFile.getFileName().toString();
                boolean enabled = !fileName.toLowerCase().endsWith(DISABLED_EXTENSION);

                // 無効化されたプラグインの場合、元のJAR名を取得
                String pluginName;
                if (enabled) {
                    pluginName = fileName.substring(0, fileName.length() - 4); // .jar を除去
                } else {
                    pluginName = fileName.substring(0, fileName.length() - DISABLED_EXTENSION.length());
                }

                Map<String, Object> pluginInfo = new HashMap<>();
                pluginInfo.put("name", pluginName);
                pluginInfo.put("fileName", fileName);
                pluginInfo.put("enabled", enabled);
                pluginInfo.put("size", Files.size(pluginFile));
                pluginInfo.put("lastModified", Files.getLastModifiedTime(pluginFile).toMillis());

                plugins.add(pluginInfo);
            }

            // レスポンスを送信
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("plugins", plugins);

            sendJsonResponse(exchange, 200, responseData);
        } catch (IOException e) {
            throw new AppException("プラグイン一覧の取得に失敗しました: " + e.getMessage(), e, 500);
        }
    }

    /**
     * プラグインの有効/無効を切り替え
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void handleTogglePlugin(HttpExchange exchange) throws IOException {
        // プラグイン名を取得
        String path = exchange.getRequestURI().getPath();
        String pluginName = path.replaceAll("^/plugins/(.+?)/toggle$", "$1");

        try {
            // プラグインディレクトリを取得
            Path pluginsDir = Paths.get(PLUGINS_DIR);
            if (!Files.exists(pluginsDir) || !Files.isDirectory(pluginsDir)) {
                throw new AppException("プラグインディレクトリが見つかりません: " + PLUGINS_DIR, 500);
            }

            // プラグインファイルを探す（有効/無効の両方を考慮）
            Path pluginJarPath = pluginsDir.resolve(pluginName + ".jar");
            Path pluginDisabledPath = pluginsDir.resolve(pluginName + DISABLED_EXTENSION);

            boolean currentlyEnabled = Files.exists(pluginJarPath);
            boolean currentlyDisabled = Files.exists(pluginDisabledPath);

            if (!currentlyEnabled && !currentlyDisabled) {
                throw new AppException("プラグインが見つかりません: " + pluginName, 404);
            }

            // 有効/無効を切り替え
            if (currentlyEnabled) {
                // 有効→無効
                Files.move(pluginJarPath, pluginDisabledPath);
                notificationServer.notifyPluginStatus(pluginName, false, "プラグインを無効化しました: " + pluginName);
                logger.info("プラグインを無効化しました: {}", pluginName);

                Map<String, Object> responseData = new HashMap<>();
                responseData.put("success", true);
                responseData.put("enabled", false);
                responseData.put("message", "プラグインを無効化しました: " + pluginName);

                sendJsonResponse(exchange, 200, responseData);
            } else {
                // 無効→有効
                Files.move(pluginDisabledPath, pluginJarPath);
                notificationServer.notifyPluginStatus(pluginName, true, "プラグインを有効化しました: " + pluginName);
                logger.info("プラグインを有効化しました: {}", pluginName);

                Map<String, Object> responseData = new HashMap<>();
                responseData.put("success", true);
                responseData.put("enabled", true);
                responseData.put("message", "プラグインを有効化しました: " + pluginName);

                sendJsonResponse(exchange, 200, responseData);
            }
        } catch (IOException e) {
            throw new AppException("プラグインの有効/無効切替に失敗しました: " + e.getMessage(), e, 500);
        }
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
     * CORSヘッダーを設定
     *
     * @param exchange HTTPExchange
     */
    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }
}