package jp.tproject.ws;

import jp.tproject.config.ConfigManager;
import jp.tproject.core.JsonUtil;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocketを使用したリアルタイム通知サーバー
 * 複数サーバー対応
 */
public class NotificationServer extends WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationServer.class);

    // 接続中のクライアントを管理（クライアントID -> WebSocket）
    private final Map<String, WebSocket> connections = new ConcurrentHashMap<>();

    // クライアントの購読情報を管理（サーバーID -> 購読クライアントIDセット）
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    /**
     * NotificationServerのインスタンスを作成
     *
     * @param port WebSocketサーバーのポート
     */
    public NotificationServer(int port) {
        super(new InetSocketAddress(port));

        // グローバル通知用の購読セットを初期化
        subscriptions.put("global", new CopyOnWriteArraySet<>());

        // すべてのサーバーIDの購読セットを初期化
        ConfigManager configManager = ConfigManager.getInstance();
        configManager.getAllServerConfigs().keySet().forEach(serverId ->
                subscriptions.put(serverId, new CopyOnWriteArraySet<>())
        );
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // クライアントIDを生成
        String clientId = UUID.randomUUID().toString();
        connections.put(clientId, conn);

        // デフォルトではグローバル通知を購読
        subscriptions.get("global").add(clientId);

        String clientAddress = conn.getRemoteSocketAddress().getAddress().getHostAddress();
        logger.info("新しいクライアント接続: {} (ID: {})", clientAddress, clientId);

        // 接続成功通知
        Map<String, Object> response = new HashMap<>();
        response.put("type", "connection");
        response.put("message", "WebSocket接続が確立されました");
        response.put("clientId", clientId);
        response.put("timestamp", System.currentTimeMillis());

        conn.send(JsonUtil.toJson(response));

        // サーバー一覧も送信
        sendServerList(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // クライアントIDを特定して削除
        String clientIdToRemove = null;
        for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
            if (entry.getValue() == conn) {
                clientIdToRemove = entry.getKey();
                break;
            }
        }

        if (clientIdToRemove != null) {
            connections.remove(clientIdToRemove);

            // すべての購読リストからクライアントを削除
            for (Set<String> clients : subscriptions.values()) {
                clients.remove(clientIdToRemove);
            }

            logger.info("クライアント切断: {} (ID: {}). コード: {}, 理由: {}, リモート起点: {}",
                    conn.getRemoteSocketAddress(), clientIdToRemove, code, reason, remote);
        } else {
            logger.warn("不明なクライアントが切断されました: {}", conn.getRemoteSocketAddress());
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // メッセージ受信時の処理
        logger.debug("クライアントからメッセージを受信: {}", message);

        try {
            Map<String, Object> data = JsonUtil.jsonToMap(message);
            String clientId = findClientId(conn);

            // pingメッセージへの応答
            if (data.containsKey("type") && "ping".equals(data.get("type"))) {
                Map<String, Object> response = new HashMap<>();
                response.put("type", "pong");
                response.put("timestamp", System.currentTimeMillis());

                conn.send(JsonUtil.toJson(response));
                return;
            }

            // 購読リクエスト処理
            if (data.containsKey("type") && "subscribe".equals(data.get("type"))) {
                handleSubscription(conn, clientId, data);
                return;
            }

            // 購読解除リクエスト処理
            if (data.containsKey("type") && "unsubscribe".equals(data.get("type"))) {
                handleUnsubscription(conn, clientId, data);
                return;
            }

        } catch (Exception e) {
            logger.warn("クライアントメッセージの処理中にエラー: {}", e.getMessage());
        }
    }

    /**
     * 購読リクエストを処理
     *
     * @param conn WebSocket接続
     * @param clientId クライアントID
     * @param data リクエストデータ
     */
    private void handleSubscription(WebSocket conn, String clientId, Map<String, Object> data) {
        if (clientId == null) return;

        // サーバーID一覧が含まれている場合
        if (data.containsKey("servers")) {
            Object serversObj = data.get("servers");
            if (serversObj instanceof List<?> serverList) {

                // すべての購読を解除
                for (Set<String> subscribers : subscriptions.values()) {
                    subscribers.remove(clientId);
                }

                // グローバル通知は常に購読
                subscriptions.get("global").add(clientId);

                // 指定されたサーバーを購読
                for (Object serverIdObj : serverList) {
                    String serverId = serverIdObj.toString();

                    if (subscriptions.containsKey(serverId)) {
                        subscriptions.get(serverId).add(clientId);
                        logger.debug("クライアント {} がサーバー {} を購読しました", clientId, serverId);
                    } else {
                        logger.warn("クライアント {} が存在しないサーバー {} を購読しようとしました", clientId, serverId);
                    }
                }

                // 購読完了通知
                Map<String, Object> response = new HashMap<>();
                response.put("type", "subscription");
                response.put("success", true);
                response.put("message", "購読が更新されました");

                conn.send(JsonUtil.toJson(response));
            }
        }
    }

    /**
     * 購読解除リクエストを処理
     *
     * @param conn WebSocket接続
     * @param clientId クライアントID
     * @param data リクエストデータ
     */
    private void handleUnsubscription(WebSocket conn, String clientId, Map<String, Object> data) {
        if (clientId == null) return;

        // サーバーID一覧が含まれている場合
        if (data.containsKey("servers")) {
            Object serversObj = data.get("servers");
            if (serversObj instanceof List<?> serverList) {

                // 指定されたサーバーの購読を解除
                for (Object serverIdObj : serverList) {
                    String serverId = serverIdObj.toString();

                    if (subscriptions.containsKey(serverId) && !serverId.equals("global")) {
                        subscriptions.get(serverId).remove(clientId);
                        logger.debug("クライアント {} がサーバー {} の購読を解除しました", clientId, serverId);
                    }
                }

                // 購読解除完了通知
                Map<String, Object> response = new HashMap<>();
                response.put("type", "unsubscription");
                response.put("success", true);
                response.put("message", "購読解除が完了しました");

                conn.send(JsonUtil.toJson(response));
            }
        }
    }

    /**
     * WebSocket接続からクライアントIDを検索
     *
     * @param conn WebSocket接続
     * @return クライアントID（見つからない場合はnull）
     */
    private String findClientId(WebSocket conn) {
        for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
            if (entry.getValue() == conn) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            String clientId = findClientId(conn);
            if (clientId != null) {
                connections.remove(clientId);

                // すべての購読リストからクライアントを削除
                for (Set<String> clients : subscriptions.values()) {
                    clients.remove(clientId);
                }
            }
        }
        logger.error("WebSocketエラー: {}", ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        logger.info("WebSocket通知サーバーが起動しました。ポート: {}", getPort());
    }

    /**
     * サーバー一覧情報を送信
     *
     * @param conn 送信先WebSocket接続
     */
    private void sendServerList(WebSocket conn) {
        Map<String, Object> serverListData = new HashMap<>();
        serverListData.put("type", "server_list");

        // サーバー情報の配列を作成
        Map<String, Map<String, Object>> servers = new HashMap<>();
        ConfigManager configManager = ConfigManager.getInstance();

        configManager.getAllServerConfigs().forEach((serverId, config) -> {
            Map<String, Object> serverInfo = new HashMap<>();
            serverInfo.put("id", serverId);
            serverInfo.put("name", config.getName());
            servers.put(serverId, serverInfo);
        });

        serverListData.put("servers", servers);
        serverListData.put("defaultServerId", configManager.getDefaultServerConfig().getId());

        conn.send(JsonUtil.toJson(serverListData));
    }

    /**
     * 指定されたサーバーの通知を送信
     *
     * @param serverId サーバーID
     * @param type 通知タイプ
     * @param message 通知メッセージ
     * @param data 追加データ
     */
    public void notifyServer(String serverId, String type, String message, Map<String, Object> data) {
        if (!subscriptions.containsKey(serverId)) {
            logger.warn("存在しないサーバーID {} への通知が要求されました", serverId);
            return;
        }

        Map<String, Object> notification = new HashMap<>();
        notification.put("type", type);
        notification.put("serverId", serverId);
        notification.put("message", message);
        notification.put("timestamp", System.currentTimeMillis());

        if (data != null) {
            notification.put("data", data);
        }

        String json = JsonUtil.toJson(notification);

        // 該当サーバーの購読者に送信
        for (String clientId : subscriptions.get(serverId)) {
            WebSocket conn = connections.get(clientId);
            if (conn != null && conn.isOpen()) {
                conn.send(json);
            }
        }
    }

    /**
     * サーバー状態変更の通知を送信
     *
     * @param serverId サーバーID
     * @param status サーバーステータス (starting, running, stopping, stopped)
     * @param message 通知メッセージ
     */
    public void notifyServerStatus(String serverId, String status, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        notifyServer(serverId, "server_status", message, data);
    }

    /**
     * グローバル通知を送信（すべてのクライアントに送信）
     *
     * @param type 通知タイプ
     * @param message 通知メッセージ
     * @param data 追加データ
     */
    public void notifyGlobal(String type, String message, Map<String, Object> data) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", type);
        notification.put("message", message);
        notification.put("timestamp", System.currentTimeMillis());

        if (data != null) {
            notification.put("data", data);
        }

        String json = JsonUtil.toJson(notification);

        // グローバル購読者に送信
        for (String clientId : subscriptions.get("global")) {
            WebSocket conn = connections.get(clientId);
            if (conn != null && conn.isOpen()) {
                conn.send(json);
            }
        }
    }

    /**
     * グローバル状態通知を送信
     *
     * @param status グローバルステータス
     * @param message 通知メッセージ
     */
    public void notifyGlobalStatus(String status, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        notifyGlobal("global_status", message, data);
    }

    /**
     * プラグイン状態変更の通知を送信
     *
     * @param serverId サーバーID
     * @param pluginName プラグイン名
     * @param enabled 有効/無効状態
     * @param message 通知メッセージ
     */
    public void notifyPluginStatus(String serverId, String pluginName, boolean enabled, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("plugin", pluginName);
        data.put("enabled", enabled);
        notifyServer(serverId, "plugin_status", message, data);
    }

    /**
     * コマンド実行通知を送信
     *
     * @param serverId サーバーID
     * @param command 実行されたコマンド
     * @param success 成功/失敗
     * @param output コマンド出力
     */
    public void notifyCommandExecution(String serverId, String command, boolean success, String output) {
        Map<String, Object> data = new HashMap<>();
        data.put("command", command);
        data.put("success", success);
        data.put("output", output);
        notifyServer(serverId, "command_execution",
                success ? "コマンドが正常に実行されました" : "コマンド実行エラー", data);
    }

    /**
     * ファイル操作通知を送信
     *
     * @param serverId サーバーID
     * @param operation 操作タイプ (upload, download, delete)
     * @param path ファイルパス
     * @param success 成功/失敗
     */
    public void notifyFileOperation(String serverId, String operation, String path, boolean success) {
        Map<String, Object> data = new HashMap<>();
        data.put("operation", operation);
        data.put("path", path);
        data.put("success", success);

        String message = switch(operation) {
            case "upload" -> success ? "ファイルが正常にアップロードされました" : "ファイルのアップロードに失敗しました";
            case "download" -> success ? "ファイルが正常にダウンロードされました" : "ファイルのダウンロードに失敗しました";
            case "delete" -> success ? "ファイルが正常に削除されました" : "ファイルの削除に失敗しました";
            default -> "ファイル操作が完了しました";
        };

        notifyServer(serverId, "file_operation", message, data);
    }

    /**
     * 後方互換性のためのメソッド
     * サーバーID未指定の場合はデフォルトサーバーを使用
     */
    public void notifyServerStatus(String status, String message) {
        String defaultServerId = ConfigManager.getInstance().getDefaultServerConfig().getId();
        notifyServerStatus(defaultServerId, status, message);
    }

    public void notifyPluginStatus(String pluginName, boolean enabled, String message) {
        String defaultServerId = ConfigManager.getInstance().getDefaultServerConfig().getId();
        notifyPluginStatus(defaultServerId, pluginName, enabled, message);
    }

    public void notifyCommandExecution(String command, boolean success, String output) {
        String defaultServerId = ConfigManager.getInstance().getDefaultServerConfig().getId();
        notifyCommandExecution(defaultServerId, command, success, output);
    }

    public void notifyFileOperation(String operation, String path, boolean success) {
        String defaultServerId = ConfigManager.getInstance().getDefaultServerConfig().getId();
        notifyFileOperation(defaultServerId, operation, path, success);
    }

    /**
     * 指定したタイプの通知をすべての接続クライアントに送信 (後方互換性用)
     */
    public void broadcast(String type, String message, Map<String, Object> data) {
        notifyGlobal(type, message, data);
    }
}