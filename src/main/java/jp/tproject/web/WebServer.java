package jp.tproject.web;

import com.sun.net.httpserver.HttpServer;
import jp.tproject.config.ConfigManager;
import jp.tproject.core.AuthFilter;
import jp.tproject.ftp.FtpManager;
import jp.tproject.minecraft.MinecraftServerManager;
import jp.tproject.web.handler.*;
import jp.tproject.ws.NotificationServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * HTTPサーバーを管理するクラス
 * 複数サーバー対応
 */
public class WebServer {

    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    private final HttpServer server;
    private final int webPort;
    private final int wsPort;
    private final NotificationServer notificationServer;
    private final MinecraftServerManager serverManager;
    private final ConfigManager configManager;

    /**
     * WebServerのインスタンスを作成
     *
     * @param configManager 設定マネージャ
     * @throws IOException 入出力例外
     */
    public WebServer(ConfigManager configManager) throws IOException {
        this.configManager = configManager;
        this.webPort = configManager.getWebPort();
        this.wsPort = configManager.getWebSocketPort();

        // MinecraftServerManagerを初期化
        this.serverManager = MinecraftServerManager.getInstance();

        // WebSocketサーバーを初期化（WebSocketポートで起動）
        this.notificationServer = new NotificationServer(wsPort);

        // HTTPサーバーを作成
        this.server = HttpServer.create(new InetSocketAddress(webPort), 0);

        // 認証フィルターを作成
        AuthFilter authFilter = new AuthFilter();

        // コンテキストに各ハンドラーとフィルターを設定
        // サーバー管理関連
        server.createContext("/servers", new ServersHandler()).getFilters().add(authFilter);
        server.createContext("/restart", new RestartHandler(notificationServer, serverManager)).getFilters().add(authFilter);
        server.createContext("/stop", new StopHandler(notificationServer, serverManager)).getFilters().add(authFilter);
        server.createContext("/command", new CommandHandler(notificationServer, serverManager)).getFilters().add(authFilter);
        server.createContext("/plugins", new PluginsHandler(notificationServer, serverManager)).getFilters().add(authFilter);

        // FTP関連のエンドポイント
        setupFtpEndpoints(authFilter);

        // スレッドプールを設定
        server.setExecutor(Executors.newFixedThreadPool(10));
    }

    /**
     * FTP関連のエンドポイントを設定
     *
     * @param authFilter 認証フィルター
     */
    private void setupFtpEndpoints(AuthFilter authFilter) throws IOException {
        // サーバーごとにFTP設定があれば設定
        for (String serverId : configManager.getAllServerConfigs().keySet()) {
            // FTPマネージャーを初期化
            FtpManager ftpManager = createFtpManager(serverId);
            if (ftpManager == null) continue;

            // サーバーIDごとのFTPエンドポイントを設定
            String basePath = "/ftp/" + serverId;

            server.createContext(basePath + "/list", new FtpListHandler(ftpManager, serverId)).getFilters().add(authFilter);
            server.createContext(basePath + "/upload", new FtpUploadHandler(ftpManager, notificationServer, serverId)).getFilters().add(authFilter);
            server.createContext(basePath + "/download", new FtpDownloadHandler(ftpManager, notificationServer, serverId)).getFilters().add(authFilter);

            logger.info("サーバー {} のFTPエンドポイントを設定しました: {}", serverId, basePath);
        }

        // 後方互換性のためのデフォルトFTPエンドポイント
        String defaultServerId = configManager.getDefaultServerConfig().getId();
        FtpManager defaultFtpManager = createFtpManager(defaultServerId);
        if (defaultFtpManager != null) {
            server.createContext("/ftp/list", new FtpListHandler(defaultFtpManager, defaultServerId)).getFilters().add(authFilter);
            server.createContext("/ftp/upload", new FtpUploadHandler(defaultFtpManager, notificationServer, defaultServerId)).getFilters().add(authFilter);
            server.createContext("/ftp/download", new FtpDownloadHandler(defaultFtpManager, notificationServer, defaultServerId)).getFilters().add(authFilter);
            logger.info("デフォルトFTPエンドポイントを設定しました: /ftp/*");
        }
    }

    /**
     * サーバーIDからFTPマネージャーを作成
     *
     * @param serverId サーバーID
     * @return FtpManagerインスタンス、設定がない場合はnull
     */
    private FtpManager createFtpManager(String serverId) {
        // サーバー固有のFTP設定を取得
        String ftpHost = getFtpConfigValue(serverId, "ftp_host", "localhost");
        String ftpPortStr = getFtpConfigValue(serverId, "ftp_port", "21");
        String ftpUser = getFtpConfigValue(serverId, "ftp_user", "anonymous");
        String ftpPassword = getFtpConfigValue(serverId, "ftp_password", "");

        try {
            int ftpPort = Integer.parseInt(ftpPortStr);
            return new FtpManager(ftpHost, ftpPort, ftpUser, ftpPassword);
        } catch (NumberFormatException e) {
            logger.error("サーバー {} のFTPポート設定が無効です: {}", serverId, ftpPortStr);
            return null;
        }
    }

    /**
     * サーバー固有のFTP設定値を取得
     *
     * @param serverId サーバーID
     * @param key 設定キー
     * @param defaultValue デフォルト値
     * @return 設定値
     */
    private String getFtpConfigValue(String serverId, String key, String defaultValue) {
        // サーバー設定から追加パラメータを取得
        String value = configManager.getServerConfig(serverId).getExtraParam(key);

        // 設定がない場合はデフォルト値を使用
        if (value == null || value.isEmpty()) {
            // 環境変数から取得を試みる
            String envKey = "FTP_" + key.toUpperCase().substring(4); // ftp_host -> FTP_HOST
            value = System.getenv(envKey);

            // 環境変数にもない場合はデフォルト値
            if (value == null || value.isEmpty()) {
                return defaultValue;
            }
        }

        return value;
    }

    /**
     * サーバーを起動
     */
    public void start() {
        // WebSocketサーバーを先に起動
        notificationServer.start();
        logger.info("WebSocket通知サーバーを起動しました。ポート: {}", wsPort);

        // HTTPサーバーを起動
        server.start();
        logger.info("HTTPサーバーを起動しました。ポート: {}", webPort);
    }

    /**
     * サーバーを停止
     */
    public void stop() {
        // 即時停止する場合は0を指定
        server.stop(0);
        logger.info("HTTPサーバーを停止しました");

        try {
            notificationServer.stop();
            logger.info("WebSocket通知サーバーを停止しました");
        } catch (Exception e) {
            logger.error("WebSocket通知サーバーの停止中にエラーが発生しました", e);
        }
    }

    /**
     * HTTPポート番号を取得
     *
     * @return ポート番号
     */
    public int getWebPort() {
        return webPort;
    }

    /**
     * WebSocketポート番号を取得
     *
     * @return ポート番号
     */
    public int getWsPort() {
        return wsPort;
    }

    /**
     * NotificationServerのインスタンスを取得
     *
     * @return NotificationServerのインスタンス
     */
    public NotificationServer getNotificationServer() {
        return notificationServer;
    }
}