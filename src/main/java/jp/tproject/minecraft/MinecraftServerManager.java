package jp.tproject.minecraft;

import jp.tproject.config.ConfigManager;
import jp.tproject.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Minecraftサーバーの操作を管理するクラス
 * 複数サーバーに対応
 */
public class MinecraftServerManager {
    private static final Logger logger = LoggerFactory.getLogger(MinecraftServerManager.class);

    // シングルトンインスタンス
    private static MinecraftServerManager instance;

    // RCONクライアントキャッシュ (サーバーID -> RconClient)
    private final Map<String, RconClient> rconClients = new ConcurrentHashMap<>();

    // サーバー状態キャッシュ (サーバーID -> 状態)
    private final Map<String, String> serverStatus = new ConcurrentHashMap<>();

    /**
     * プライベートコンストラクタ
     */
    private MinecraftServerManager() {
        // 初期化処理
        initServerStatus();
    }

    /**
     * シングルトンインスタンスを取得
     *
     * @return MinecraftServerManagerインスタンス
     */
    public static synchronized MinecraftServerManager getInstance() {
        if (instance == null) {
            instance = new MinecraftServerManager();
        }
        return instance;
    }

    /**
     * サーバー状態の初期化
     */
    private void initServerStatus() {
        ConfigManager configManager = ConfigManager.getInstance();

        // すべてのサーバー設定を取得
        Map<String, ServerConfig> allServers = configManager.getAllServerConfigs();

        // サーバー状態を初期化
        for (String serverId : allServers.keySet()) {
            // サーバー状態を確認して設定
            boolean isRunning = isServerRunning(serverId);
            serverStatus.put(serverId, isRunning ? "running" : "stopped");

            logger.info("サーバー状態を初期化しました: {} - {}", serverId, isRunning ? "実行中" : "停止中");
        }
    }

    /**
     * 指定されたサーバーIDのRCONクライアントを取得
     *
     * @param serverId サーバーID
     * @return RconClientインスタンス
     * @throws IOException 接続エラーが発生した場合
     */
    private synchronized RconClient getRconClient(String serverId) throws IOException {
        // キャッシュされたクライアントがあれば返却
        if (rconClients.containsKey(serverId)) {
            RconClient client = rconClients.get(serverId);

            // 接続状態をチェック
            if (client.isConnected()) {
                return client;
            } else {
                // 切断されていれば削除
                rconClients.remove(serverId);
            }
        }

        // サーバー設定を取得
        ServerConfig config = ConfigManager.getInstance().getServerConfig(serverId);
        if (config == null) {
            throw new IOException("サーバー設定が見つかりません: " + serverId);
        }

        // 新しいRCONクライアントを作成
        RconClient client = new RconClient(
                config.getRconHost(),
                config.getRconPort(),
                config.getRconPassword()
        );

        // 接続して認証
        if (client.connect()) {
            // 成功したらキャッシュに追加
            rconClients.put(serverId, client);
            return client;
        } else {
            throw new IOException("RCONサーバーに接続できませんでした: " + serverId);
        }
    }

    /**
     * 特定のサーバーにコマンドを実行
     *
     * @param serverId サーバーID
     * @param command 実行するコマンド
     * @return コマンド実行結果
     * @throws IOException 通信エラーが発生した場合
     */
    public String executeCommand(String serverId, String command) throws IOException {
        try {
            RconClient client = getRconClient(serverId);
            logger.info("サーバー {} にコマンドを実行します: {}", serverId, command);
            return client.executeCommand(command);
        } catch (IOException e) {
            logger.error("サーバー {} のコマンド実行中にエラーが発生しました: {}", serverId, e.getMessage());
            throw e;
        }
    }

    /**
     * サーバーを起動
     *
     * @param serverId サーバーID
     * @return 成功時true
     */
    public boolean startServer(String serverId) {
        try {
            // サーバー設定を取得
            ServerConfig config = ConfigManager.getInstance().getServerConfig(serverId);
            if (config == null) {
                logger.error("サーバー設定が見つかりません: {}", serverId);
                return false;
            }

            // サーバーが既に動作中かチェック
            if (isServerRunning(serverId)) {
                logger.info("サーバー {} は既に実行中です", serverId);
                return true;
            }

            logger.info("サーバー {} を起動しています", serverId);
            serverStatus.put(serverId, "starting");

            // 起動コマンドを実行
            Process process = executeSystemCommand(config.getServerStartCommand());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("サーバー {} の起動コマンドを正常に実行しました", serverId);
                serverStatus.put(serverId, "running");
                return true;
            } else {
                logger.error("サーバー {} の起動コマンドが失敗しました。終了コード: {}", serverId, exitCode);
                serverStatus.put(serverId, "error");
                return false;
            }
        } catch (Exception e) {
            logger.error("サーバー {} の起動中にエラーが発生しました", serverId, e);
            serverStatus.put(serverId, "error");
            return false;
        }
    }

    /**
     * サーバーを停止
     *
     * @param serverId サーバーID
     * @return 成功時true
     */
    public boolean stopServer(String serverId) {
        try {
            // サーバー設定を取得
            ServerConfig config = ConfigManager.getInstance().getServerConfig(serverId);
            if (config == null) {
                logger.error("サーバー設定が見つかりません: {}", serverId);
                return false;
            }

            // サーバーが動作中でなければそのまま成功
            if (!isServerRunning(serverId)) {
                logger.info("サーバー {} は既に停止しています", serverId);
                return true;
            }

            serverStatus.put(serverId, "stopping");

            // まず、RCONでsaveコマンドを実行
            try {
                executeCommand(serverId, "save-all");
                logger.info("サーバー {} のワールドの保存を実行しました", serverId);
                // 少し待機してセーブが完了するのを待つ
                Thread.sleep(2000);
            } catch (Exception e) {
                logger.warn("サーバー {} のワールドの保存中にエラーが発生しました", serverId, e);
                // 続行（サーバーがすでに停止している可能性がある）
            }

            // 次に、RCONでstopコマンドを試す（失敗してもOK）
            try {
                executeCommand(serverId, "stop");
                logger.info("サーバー {} の停止コマンドをゲーム内で実行しました", serverId);
                // サーバーが停止するまで少し待機
                Thread.sleep(5000);
            } catch (Exception e) {
                logger.warn("サーバー {} のゲーム内停止コマンドの実行中にエラーが発生しました", serverId, e);
                // 続行（システムコマンドでの停止にフォールバック）
            }

            // システムコマンドでの停止
            logger.info("サーバー {} を停止しています", serverId);
            Process process = executeSystemCommand(config.getServerStopCommand());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("サーバー {} の停止コマンドを正常に実行しました", serverId);
                serverStatus.put(serverId, "stopped");

                // RCONクライアントを削除
                synchronized (this) {
                    if (rconClients.containsKey(serverId)) {
                        rconClients.get(serverId).close();
                        rconClients.remove(serverId);
                    }
                }

                return true;
            } else {
                logger.error("サーバー {} の停止コマンドが失敗しました。終了コード: {}", serverId, exitCode);
                serverStatus.put(serverId, "error");
                return false;
            }
        } catch (Exception e) {
            logger.error("サーバー {} の停止中にエラーが発生しました", serverId, e);
            serverStatus.put(serverId, "error");
            return false;
        }
    }

    /**
     * サーバーを再起動
     *
     * @param serverId サーバーID
     * @return 成功時true
     */
    public boolean restartServer(String serverId) {
        try {
            // サーバー設定を取得
            ServerConfig config = ConfigManager.getInstance().getServerConfig(serverId);
            if (config == null) {
                logger.error("サーバー設定が見つかりません: {}", serverId);
                return false;
            }

            serverStatus.put(serverId, "restarting");

            // まず、RCONでsaveコマンドを実行（サーバーが動作中の場合のみ）
            if (isServerRunning(serverId)) {
                try {
                    executeCommand(serverId, "save-all");
                    logger.info("サーバー {} のワールドの保存を実行しました", serverId);
                    // 少し待機してセーブが完了するのを待つ
                    Thread.sleep(2000);
                } catch (Exception e) {
                    logger.warn("サーバー {} のワールドの保存中にエラーが発生しました", serverId, e);
                    // 続行
                }
            }

            // システムコマンドでの再起動
            logger.info("サーバー {} を再起動しています", serverId);
            Process process = executeSystemCommand(config.getServerRestartCommand());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("サーバー {} の再起動コマンドを正常に実行しました", serverId);
                serverStatus.put(serverId, "starting");

                // 古いRCONクライアントを削除
                synchronized (this) {
                    if (rconClients.containsKey(serverId)) {
                        rconClients.get(serverId).close();
                        rconClients.remove(serverId);
                    }
                }

                // 起動完了まで少し待機
                Thread.sleep(5000);

                // 起動したことを確認
                if (isServerRunning(serverId)) {
                    serverStatus.put(serverId, "running");
                } else {
                    // 少し待ってもう一度確認
                    Thread.sleep(5000);
                    if (isServerRunning(serverId)) {
                        serverStatus.put(serverId, "running");
                    } else {
                        logger.warn("サーバー {} の再起動後、実行状態を確認できませんでした", serverId);
                        serverStatus.put(serverId, "unknown");
                    }
                }

                return true;
            } else {
                logger.error("サーバー {} の再起動コマンドが失敗しました。終了コード: {}", serverId, exitCode);
                serverStatus.put(serverId, "error");
                return false;
            }
        } catch (Exception e) {
            logger.error("サーバー {} の再起動中にエラーが発生しました", serverId, e);
            serverStatus.put(serverId, "error");
            return false;
        }
    }

    /**
     * すべてのサーバーを起動
     *
     * @return 成功したサーバーID一覧
     */
    public List<String> startAllServers() {
        List<String> successServers = new ArrayList<>();
        ConfigManager configManager = ConfigManager.getInstance();

        for (ServerConfig config : configManager.getAllServerConfigs().values()) {
            String serverId = config.getId();
            if (startServer(serverId)) {
                successServers.add(serverId);
            }
        }

        return successServers;
    }

    /**
     * すべてのサーバーを停止
     *
     * @return 成功したサーバーID一覧
     */
    public List<String> stopAllServers() {
        List<String> successServers = new ArrayList<>();
        ConfigManager configManager = ConfigManager.getInstance();

        for (ServerConfig config : configManager.getAllServerConfigs().values()) {
            String serverId = config.getId();
            if (stopServer(serverId)) {
                successServers.add(serverId);
            }
        }

        return successServers;
    }

    /**
     * すべてのサーバーを再起動
     *
     * @return 成功したサーバーID一覧
     */
    public List<String> restartAllServers() {
        List<String> successServers = new ArrayList<>();
        ConfigManager configManager = ConfigManager.getInstance();

        for (ServerConfig config : configManager.getAllServerConfigs().values()) {
            String serverId = config.getId();
            if (restartServer(serverId)) {
                successServers.add(serverId);
            }
        }

        return successServers;
    }

    /**
     * 指定されたサーバーの状態を取得
     *
     * @param serverId サーバーID
     * @return サーバー状態（running, stopped, starting, stopping, restarting, error, unknown）
     */
    public String getServerStatus(String serverId) {
        return serverStatus.getOrDefault(serverId, "unknown");
    }

    /**
     * すべてのサーバー状態を取得
     *
     * @return サーバーID -> 状態のマップ
     */
    public Map<String, String> getAllServerStatus() {
        return new HashMap<>(serverStatus);
    }

    /**
     * オペレーティングシステムコマンドを実行
     *
     * @param command 実行するコマンド
     * @return Processオブジェクト
     * @throws IOException コマンド実行に失敗した場合
     */
    private Process executeSystemCommand(String command) throws IOException {
        logger.debug("システムコマンドを実行: {}", command);

        List<String> commandArgs = new ArrayList<>();

        // UNIXシステムではシェル経由で実行
        if (isUnix()) {
            commandArgs.add("/bin/sh");
            commandArgs.add("-c");
            commandArgs.add(command);
        } else {
            // Windowsではcmdで実行
            commandArgs.add("cmd.exe");
            commandArgs.add("/c");
            commandArgs.add(command);
        }

        ProcessBuilder pb = new ProcessBuilder(commandArgs);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 非同期で出力を読み取り
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("コマンド出力: {}", line);
                }
            } catch (IOException e) {
                logger.error("コマンド出力の読み取り中にエラーが発生しました", e);
            }
        }).start();

        return process;
    }

    /**
     * 現在のOSがUNIX系かどうかを判定
     *
     * @return UNIX系OSの場合true
     */
    private boolean isUnix() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("nix") || os.contains("nux") || os.contains("mac");
    }

    /**
     * プラグインの有効/無効を切り替え
     *
     * @param serverId サーバーID
     * @param pluginName プラグイン名
     * @param enable 有効化する場合true、無効化する場合false
     * @return 成功時true
     */
    public boolean togglePlugin(String serverId, String pluginName, boolean enable) {
        try {
            // サーバーが実行中か確認
            if (!isServerRunning(serverId)) {
                logger.warn("プラグイン操作失敗: サーバー {} は実行中ではありません", serverId);
                return false;
            }

            // プラグインコマンドを実行
            String command = enable ? "plugin enable " + pluginName : "plugin disable " + pluginName;
            String result = executeCommand(serverId, command);

            // 成功したかどうかを結果から判断
            boolean success = !result.toLowerCase().contains("error") && !result.toLowerCase().contains("unknown");

            if (success) {
                logger.info("サーバー {} のプラグイン {} を {} しました: {}",
                        serverId, pluginName, enable ? "有効化" : "無効化", result);
            } else {
                logger.warn("サーバー {} のプラグイン {} の {} に失敗しました: {}",
                        serverId, pluginName, enable ? "有効化" : "無効化", result);
            }

            return success;
        } catch (Exception e) {
            logger.error("サーバー {} のプラグイン {} の {} 中にエラーが発生しました",
                    serverId, pluginName, enable ? "有効化" : "無効化", e);
            return false;
        }
    }

    /**
     * サーバーが現在動作しているかを確認
     *
     * @param serverId サーバーID
     * @return サーバーが動作している場合true
     */
    public boolean isServerRunning(String serverId) {
        // サーバー設定を取得
        ServerConfig config = ConfigManager.getInstance().getServerConfig(serverId);
        if (config == null) {
            logger.error("サーバー設定が見つかりません: {}", serverId);
            return false;
        }

        try (RconClient rcon = new RconClient(config.getRconHost(), config.getRconPort(), config.getRconPassword())) {
            boolean connected = rcon.connect();

            // 接続が成功した場合、サーバーは実行中
            if (connected) {
                serverStatus.put(serverId, "running");
            } else {
                serverStatus.put(serverId, "stopped");
            }

            return connected;
        } catch (Exception e) {
            logger.debug("サーバー {} が動作していないようです: {}", serverId, e.getMessage());
            serverStatus.put(serverId, "stopped");
            return false;
        }
    }

    /**
     * 指定されたサーバーのプラグインディレクトリパスを取得
     *
     * @param serverId サーバーID
     * @return プラグインディレクトリパス
     */
    public String getPluginsDirectory(String serverId) {
        ServerConfig config = ConfigManager.getInstance().getServerConfig(serverId);
        if (config == null) {
            return "./plugins"; // デフォルト値
        }
        return config.getPluginsDirectory();
    }

    /**
     * 全てのRCON接続を閉じる
     */
    public void closeAllConnections() {
        synchronized (this) {
            for (RconClient client : rconClients.values()) {
                try {
                    client.close();
                } catch (Exception e) {
                    logger.warn("RCON接続のクローズに失敗しました", e);
                }
            }
            rconClients.clear();
        }
    }
}