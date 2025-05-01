package jp.tproject;

import jp.tproject.config.ConfigManager;
import jp.tproject.config.ServerConfig;
import jp.tproject.minecraft.MinecraftServerManager;
import jp.tproject.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * メインアプリケーションクラス
 * 複数サーバー対応版
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * アプリケーションのエントリーポイント
     *
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        logger.info("マインクラフトサーバー制御システムを起動しています...");

        try {
            // 設定ファイルを読み込み
            ConfigManager configManager = ConfigManager.getInstance();

            // 設定ファイルからポート番号を取得
            int webPort = configManager.getWebPort();
            int wsPort = configManager.getWebSocketPort();

            // サーバー設定を表示
            displayServerConfigs(configManager);

            // WebServerを作成
            WebServer webServer = new WebServer(configManager);

            // シャットダウンフックを登録
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("サーバーをシャットダウンしています...");

                // MincraftServerManagerの接続をクローズ
                MinecraftServerManager.getInstance().closeAllConnections();

                // WebServerを停止
                webServer.stop();
                logger.info("シャットダウンが完了しました");
            }));

            // サーバーを起動
            webServer.start();
            logger.info("Web APIサーバーを起動しました。ポート: {}", webPort);
            logger.info("WebSocketサーバーを起動しました。ポート: {}", wsPort);

            // 停止コマンドを待機
            waitForExitCommand();

            // サーバーを停止
            webServer.stop();
            MinecraftServerManager.getInstance().closeAllConnections();
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
     * サーバー設定情報を表示
     *
     * @param configManager 設定マネージャー
     */
    private static void displayServerConfigs(ConfigManager configManager) {
        Map<String, ServerConfig> allConfigs = configManager.getAllServerConfigs();
        String defaultServerId = configManager.getDefaultServerConfig().getId();

        logger.info("読み込まれたサーバー設定 ({})", allConfigs.size());
        logger.info("デフォルトサーバー: {}", defaultServerId);

        for (Map.Entry<String, ServerConfig> entry : allConfigs.entrySet()) {
            ServerConfig config = entry.getValue();
            String isDefault = entry.getKey().equals(defaultServerId) ? " (デフォルト)" : "";

            logger.info(" - サーバー: {} - {}{}", config.getId(), config.getName(), isDefault);
            logger.info("   RCON: {}:{}", config.getRconHost(), config.getRconPort());
            logger.info("   プラグインディレクトリ: {}", config.getPluginsDirectory());
        }
    }

    /**
     * コンソールからの停止コマンドを待機
     */
    private static void waitForExitCommand() {
        Thread consoleThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            printHelpCommand();

            while (true) {
                System.out.print("Command> ");
                String command = scanner.nextLine().trim().toLowerCase();

                if (command.equals("exit") || command.equals("quit")) {
                    System.out.println("サーバーを停止します...");
                    break;
                } else if (command.equals("help")) {
                    printHelpCommand();
                } else if (command.equals("status")) {
                    printServerStatus();
                } else if (command.equals("list")) {
                    printServerList();
                } else if (command.startsWith("restart ")) {
                    String serverId = command.substring("restart ".length()).trim();
                    restartServer(serverId);
                } else if (command.equals("restart-all")) {
                    restartAllServers();
                } else if (command.startsWith("stop ")) {
                    String serverId = command.substring("stop ".length()).trim();
                    stopServer(serverId);
                } else if (command.equals("stop-all")) {
                    stopAllServers();
                } else if (command.startsWith("start ")) {
                    String serverId = command.substring("start ".length()).trim();
                    startServer(serverId);
                } else if (command.equals("start-all")) {
                    startAllServers();
                } else if (command.equals("reload-config")) {
                    reloadConfig();
                } else {
                    System.out.println("不明なコマンドです。'help'を入力するとコマンド一覧が表示されます。");
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

    /**
     * ヘルプコマンドを表示
     */
    private static void printHelpCommand() {
        System.out.println("=====================================================");
        System.out.println("マインクラフトサーバー制御システム - コマンド一覧");
        System.out.println("=====================================================");
        System.out.println("help          - このヘルプを表示");
        System.out.println("list          - サーバー一覧を表示");
        System.out.println("status        - すべてのサーバーの状態を表示");
        System.out.println("start <id>    - 指定IDのサーバーを起動");
        System.out.println("start-all     - すべてのサーバーを起動");
        System.out.println("stop <id>     - 指定IDのサーバーを停止");
        System.out.println("stop-all      - すべてのサーバーを停止");
        System.out.println("restart <id>  - 指定IDのサーバーを再起動");
        System.out.println("restart-all   - すべてのサーバーを再起動");
        System.out.println("reload-config - 設定を再読み込み");
        System.out.println("exit/quit     - システムを終了");
        System.out.println("=====================================================");
    }

    /**
     * サーバー一覧を表示
     */
    private static void printServerList() {
        ConfigManager configManager = ConfigManager.getInstance();
        Map<String, ServerConfig> allConfigs = configManager.getAllServerConfigs();
        String defaultServerId = configManager.getDefaultServerConfig().getId();

        System.out.println("==== サーバー一覧 ====");
        for (Map.Entry<String, ServerConfig> entry : allConfigs.entrySet()) {
            ServerConfig config = entry.getValue();
            String isDefault = entry.getKey().equals(defaultServerId) ? " (デフォルト)" : "";

            System.out.printf("ID: %s - %s%s\n", config.getId(), config.getName(), isDefault);
            System.out.printf("  RCON: %s:%d\n", config.getRconHost(), config.getRconPort());
        }
        System.out.println("====================");
    }

    /**
     * サーバー状態を表示
     */
    private static void printServerStatus() {
        ConfigManager configManager = ConfigManager.getInstance();
        MinecraftServerManager serverManager = MinecraftServerManager.getInstance();

        Map<String, ServerConfig> allConfigs = configManager.getAllServerConfigs();
        Map<String, String> allStatus = serverManager.getAllServerStatus();

        System.out.println("==== サーバー状態 ====");
        for (Map.Entry<String, ServerConfig> entry : allConfigs.entrySet()) {
            String serverId = entry.getKey();
            ServerConfig config = entry.getValue();
            String status = allStatus.getOrDefault(serverId, "unknown");

            String statusText = switch(status) {
                case "running" -> "実行中";
                case "stopped" -> "停止中";
                case "starting" -> "起動中";
                case "stopping" -> "停止処理中";
                case "restarting" -> "再起動中";
                case "error" -> "エラー";
                default -> "不明";
            };

            System.out.printf("%s (%s): %s\n", serverId, config.getName(), statusText);
        }
        System.out.println("=====================");
    }

    /**
     * 設定を再読み込み
     */
    private static void reloadConfig() {
        try {
            ConfigManager.getInstance().loadAllConfigs();
            System.out.println("設定を再読み込みしました");
        } catch (Exception e) {
            System.out.println("設定の再読み込みに失敗しました: " + e.getMessage());
        }
    }

    /**
     * サーバーを再起動
     */
    private static void restartServer(String serverId) {
        try {
            ConfigManager configManager = ConfigManager.getInstance();

            if (serverId == null || serverId.isEmpty()) {
                serverId = configManager.getDefaultServerConfig().getId();
            }

            if (configManager.getServerConfig(serverId) == null) {
                System.out.println("サーバーIDが見つかりません: " + serverId);
                return;
            }

            System.out.println("サーバー " + serverId + " を再起動しています...");
            boolean success = MinecraftServerManager.getInstance().restartServer(serverId);

            if (success) {
                System.out.println("サーバー " + serverId + " の再起動コマンドを実行しました");
            } else {
                System.out.println("サーバー " + serverId + " の再起動に失敗しました");
            }
        } catch (Exception e) {
            System.out.println("サーバー再起動中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * すべてのサーバーを再起動
     */
    private static void restartAllServers() {
        try {
            System.out.println("すべてのサーバーを再起動しています...");
            List<String> successServers = MinecraftServerManager.getInstance().restartAllServers();

            System.out.println("再起動コマンドを実行しました。成功: " + successServers.size() + " サーバー");
            for (String serverId : successServers) {
                System.out.println(" - " + serverId);
            }
        } catch (Exception e) {
            System.out.println("サーバー再起動中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * サーバーを停止
     */
    private static void stopServer(String serverId) {
        try {
            ConfigManager configManager = ConfigManager.getInstance();

            if (serverId == null || serverId.isEmpty()) {
                serverId = configManager.getDefaultServerConfig().getId();
            }

            if (configManager.getServerConfig(serverId) == null) {
                System.out.println("サーバーIDが見つかりません: " + serverId);
                return;
            }

            System.out.println("サーバー " + serverId + " を停止しています...");
            boolean success = MinecraftServerManager.getInstance().stopServer(serverId);

            if (success) {
                System.out.println("サーバー " + serverId + " の停止コマンドを実行しました");
            } else {
                System.out.println("サーバー " + serverId + " の停止に失敗しました");
            }
        } catch (Exception e) {
            System.out.println("サーバー停止中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * すべてのサーバーを停止
     */
    private static void stopAllServers() {
        try {
            System.out.println("すべてのサーバーを停止しています...");
            List<String> successServers = MinecraftServerManager.getInstance().stopAllServers();

            System.out.println("停止コマンドを実行しました。成功: " + successServers.size() + " サーバー");
            for (String serverId : successServers) {
                System.out.println(" - " + serverId);
            }
        } catch (Exception e) {
            System.out.println("サーバー停止中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * サーバーを起動
     */
    private static void startServer(String serverId) {
        try {
            ConfigManager configManager = ConfigManager.getInstance();

            if (serverId == null || serverId.isEmpty()) {
                serverId = configManager.getDefaultServerConfig().getId();
            }

            if (configManager.getServerConfig(serverId) == null) {
                System.out.println("サーバーIDが見つかりません: " + serverId);
                return;
            }

            System.out.println("サーバー " + serverId + " を起動しています...");
            boolean success = MinecraftServerManager.getInstance().startServer(serverId);

            if (success) {
                System.out.println("サーバー " + serverId + " の起動コマンドを実行しました");
            } else {
                System.out.println("サーバー " + serverId + " の起動に失敗しました");
            }
        } catch (Exception e) {
            System.out.println("サーバー起動中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * すべてのサーバーを起動
     */
    private static void startAllServers() {
        try {
            System.out.println("すべてのサーバーを起動しています...");
            List<String> successServers = MinecraftServerManager.getInstance().startAllServers();

            System.out.println("起動コマンドを実行しました。成功: " + successServers.size() + " サーバー");
            for (String serverId : successServers) {
                System.out.println(" - " + serverId);
            }
        } catch (Exception e) {
            System.out.println("サーバー起動中にエラーが発生しました: " + e.getMessage());
        }
    }
}