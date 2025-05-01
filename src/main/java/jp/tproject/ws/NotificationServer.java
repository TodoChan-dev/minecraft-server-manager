package jp.tproject.ws;

import jp.tproject.core.JsonUtil;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocketを使用したリアルタイム通知サーバー
 */
public class NotificationServer extends WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationServer.class);

    // 接続中のクライアントを管理
    private final Set<WebSocket> connections = new CopyOnWriteArraySet<>();

    /**
     * NotificationServerのインスタンスを作成
     *
     * @param port WebSocketサーバーのポート
     */
    public NotificationServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.add(conn);
        String clientAddress = conn.getRemoteSocketAddress().getAddress().getHostAddress();
        logger.info("新しいクライアント接続: {}", clientAddress);

        // 接続成功通知
        Map<String, Object> response = new HashMap<>();
        response.put("type", "connection");
        response.put("message", "WebSocket接続が確立されました");
        response.put("timestamp", System.currentTimeMillis());

        conn.send(JsonUtil.toJson(response));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        logger.info("クライアント切断: {}. コード: {}, 理由: {}, リモート起点: {}",
                conn.getRemoteSocketAddress(), code, reason, remote);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // メッセージ受信時の処理
        // 基本的にはサーバーからクライアントへの一方通行を想定
        logger.debug("クライアントからメッセージを受信: {}", message);

        // クライアントからのメッセージに応答（例: pingに対するpong）
        try {
            Map<String, Object> data = JsonUtil.jsonToMap(message);
            if (data.containsKey("type") && "ping".equals(data.get("type"))) {
                Map<String, Object> response = new HashMap<>();
                response.put("type", "pong");
                response.put("timestamp", System.currentTimeMillis());

                conn.send(JsonUtil.toJson(response));
            }
        } catch (Exception e) {
            logger.warn("クライアントメッセージの処理中にエラー: {}", e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            connections.remove(conn);
        }
        logger.error("WebSocketエラー: {}", ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        logger.info("WebSocket通知サーバーが起動しました。ポート: {}", getPort());
    }

    /**
     * すべての接続クライアントに通知を送信
     *
     * @param type 通知タイプ
     * @param message 通知メッセージ
     * @param data 追加データ
     */
    public void broadcast(String type, String message, Map<String, Object> data) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", type);
        notification.put("message", message);
        notification.put("timestamp", System.currentTimeMillis());

        if (data != null) {
            notification.put("data", data);
        }

        String json = JsonUtil.toJson(notification);
        broadcast(json);
    }

    /**
     * サーバー状態変更の通知を送信
     *
     * @param status サーバーステータス (starting, running, stopping, stopped)
     * @param message 通知メッセージ
     */
    public void notifyServerStatus(String status, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        broadcast("server_status", message, data);
    }

    /**
     * プラグイン状態変更の通知を送信
     *
     * @param pluginName プラグイン名
     * @param enabled 有効/無効状態
     * @param message 通知メッセージ
     */
    public void notifyPluginStatus(String pluginName, boolean enabled, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("plugin", pluginName);
        data.put("enabled", enabled);
        broadcast("plugin_status", message, data);
    }

    /**
     * コマンド実行通知を送信
     *
     * @param command 実行されたコマンド
     * @param success 成功/失敗
     * @param output コマンド出力
     */
    public void notifyCommandExecution(String command, boolean success, String output) {
        Map<String, Object> data = new HashMap<>();
        data.put("command", command);
        data.put("success", success);
        data.put("output", output);
        broadcast("command_execution", success ? "コマンドが正常に実行されました" : "コマンド実行エラー", data);
    }

    /**
     * ファイル操作通知を送信
     *
     * @param operation 操作タイプ (upload, download, delete)
     * @param path ファイルパス
     * @param success 成功/失敗
     */
    public void notifyFileOperation(String operation, String path, boolean success) {
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

        broadcast("file_operation", message, data);
    }
}