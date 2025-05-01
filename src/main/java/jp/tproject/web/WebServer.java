package jp.tproject.web;

import com.sun.net.httpserver.HttpServer;
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
 */
public class WebServer {

    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    private static final int DEFAULT_PORT = 3031;
    private static final int DEFAULT_BACKLOG = 0; // 0はシステムデフォルト値を使用

    private final HttpServer server;
    private final int port;
    private final NotificationServer notificationServer;
    private final FtpManager ftpManager;
    private final MinecraftServerManager serverManager;

    /**
     * WebServerのインスタンスを作成
     *
     * @param port HTTPサーバーのポート
     * @param ftpHost FTPサーバーのホスト
     * @param ftpPort FTPサーバーのポート
     * @param ftpUser FTPサーバーのユーザー名
     * @param ftpPassword FTPサーバーのパスワード
     * @throws IOException 入出力例外
     */
    public WebServer(int port, String ftpHost, int ftpPort, String ftpUser, String ftpPassword) throws IOException {
        this.port = port;

        // FTPマネージャーを初期化
        this.ftpManager = new FtpManager(ftpHost, ftpPort, ftpUser, ftpPassword);

        // WebSocketサーバーを初期化（HTTPサーバーと同じポートを使用）
        this.notificationServer = new NotificationServer(port + 1); // WebSocketは別ポート(3032)で起動

        // MinecraftServerManagerを初期化
        this.serverManager = MinecraftServerManager.fromEnvironment();

        // HTTPサーバーを作成
        this.server = HttpServer.create(new InetSocketAddress(port), DEFAULT_BACKLOG);

        // 認証フィルターを作成
        AuthFilter authFilter = new AuthFilter();

        // コンテキストに各ハンドラーとフィルターを設定
        server.createContext("/restart", new RestartHandler(notificationServer, serverManager)).getFilters().add(authFilter);
        server.createContext("/stop", new StopHandler(notificationServer, serverManager)).getFilters().add(authFilter);
        server.createContext("/command", new CommandHandler(notificationServer, serverManager)).getFilters().add(authFilter);
        server.createContext("/plugins", new PluginsHandler(notificationServer, serverManager)).getFilters().add(authFilter);

        // FTP関連のエンドポイント
        server.createContext("/ftp/list", new FtpListHandler(ftpManager)).getFilters().add(authFilter);
        server.createContext("/ftp/upload", new FtpUploadHandler(ftpManager, notificationServer)).getFilters().add(authFilter);
        server.createContext("/ftp/download", new FtpDownloadHandler(ftpManager, notificationServer)).getFilters().add(authFilter);

        // スレッドプールを設定
        server.setExecutor(Executors.newFixedThreadPool(10));
    }

    /**
     * デフォルト設定でWebServerのインスタンスを作成
     *
     * @throws IOException 入出力例外
     */
    public WebServer() throws IOException {
        this(
                DEFAULT_PORT,
                System.getenv("FTP_HOST") != null ? System.getenv("FTP_HOST") : "localhost",
                System.getenv("FTP_PORT") != null ? Integer.parseInt(System.getenv("FTP_PORT")) : 21,
                System.getenv("FTP_USER") != null ? System.getenv("FTP_USER") : "anonymous",
                System.getenv("FTP_PASSWORD") != null ? System.getenv("FTP_PASSWORD") : ""
        );
    }

    /**
     * サーバーを起動
     */
    public void start() {
        // WebSocketサーバーを先に起動
        notificationServer.start();
        logger.info("WebSocket通知サーバーを起動しました。ポート: {}", port + 1);

        // HTTPサーバーを起動
        server.start();
        logger.info("HTTPサーバーを起動しました。ポート: {}", port);
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
     * 現在のポート番号を取得
     *
     * @return ポート番号
     */
    public int getPort() {
        return port;
    }

    /**
     * NotificationServerのインスタンスを取得
     *
     * @return NotificationServerのインスタンス
     */
    public NotificationServer getNotificationServer() {
        return notificationServer;
    }

    /**
     * FtpManagerのインスタンスを取得
     *
     * @return FtpManagerのインスタンス
     */
    public FtpManager getFtpManager() {
        return ftpManager;
    }
}