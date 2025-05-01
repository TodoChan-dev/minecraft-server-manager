package jp.tproject;

import jp.tproject.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Scanner;

/**
 * メインアプリケーションクラス
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * アプリケーションのエントリーポイント
     *
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        logger.info("サーバー制御システムを起動しています...");

        try {
            // 環境変数からポート番号を取得
            int port = getPortFromEnv();

            // FTP接続情報を環境変数から取得
            String ftpHost = System.getenv("FTP_HOST") != null ? System.getenv("FTP_HOST") : "localhost";
            int ftpPort = System.getenv("FTP_PORT") != null ? Integer.parseInt(System.getenv("FTP_PORT")) : 21;
            String ftpUser = System.getenv("FTP_USER") != null ? System.getenv("FTP_USER") : "anonymous";
            String ftpPassword = System.getenv("FTP_PASSWORD") != null ? System.getenv("FTP_PASSWORD") : "";

            // WebServerを作成
            WebServer webServer = new WebServer(port, ftpHost, ftpPort, ftpUser, ftpPassword);

            // シャットダウンフックを登録
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("サーバーをシャットダウンしています...");
                webServer.stop();
                logger.info("シャットダウンが完了しました");
            }));

            // サーバーを起動
            webServer.start();
            logger.info("サーバー制御システムを起動しました。ポート: {}", port);

            // 停止コマンドを待機
            waitForExitCommand();

            // サーバーを停止
            webServer.stop();
            logger.info("サーバー制御システムを停止しました");

        } catch (IOException e) {
            logger.error("サーバーの起動に失敗しました", e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("予期しないエラーが発生しました", e);
            System.exit(1);
        }
    }

    /**
     * 環境変数からポート番号を取得
     *
     * @return ポート番号
     */
    private static int getPortFromEnv() {
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                return Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                logger.warn("環境変数PORTの値が不正です: {}", portEnv);
            }
        }
        return 3031; // デフォルトポート
    }

    /**
     * コンソールからの停止コマンドを待機
     */
    private static void waitForExitCommand() {
        Thread consoleThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            System.out.println("サーバー制御システムを停止するには 'exit' または 'quit' と入力してください");

            while (true) {
                String command = scanner.nextLine().trim().toLowerCase();
                if (command.equals("exit") || command.equals("quit")) {
                    System.out.println("サーバーを停止します...");
                    break;
                }
            }

            scanner.close();
        });

        consoleThread.setDaemon(false);
        consoleThread.start();

        try {
            consoleThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}