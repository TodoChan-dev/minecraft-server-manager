package jp.tproject.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Minecraftサーバーの操作を管理するクラス
 */
public class MinecraftServerManager {
    private static final Logger logger = LoggerFactory.getLogger(MinecraftServerManager.class);

    private final String rconHost;
    private final int rconPort;
    private final String rconPassword;

    private final String serverStartCommand;
    private final String serverStopCommand;
    private final String serverRestartCommand;

    /**
     * MinecraftServerManagerを初期化
     *
     * @param rconHost RCONホスト
     * @param rconPort RCONポート
     * @param rconPassword RCONパスワード
     * @param serverStartCommand サーバー起動コマンド
     * @param serverStopCommand サーバー停止コマンド
     * @param serverRestartCommand サーバー再起動コマンド
     */
    public MinecraftServerManager(String rconHost, int rconPort, String rconPassword,
                                  String serverStartCommand, String serverStopCommand, String serverRestartCommand) {
        this.rconHost = rconHost;
        this.rconPort = rconPort;
        this.rconPassword = rconPassword;
        this.serverStartCommand = serverStartCommand;
        this.serverStopCommand = serverStopCommand;
        this.serverRestartCommand = serverRestartCommand;
    }

    /**
     * 環境変数からMinecraftServerManagerを作成
     *
     * @return MinecraftServerManagerのインスタンス
     */
    public static MinecraftServerManager fromEnvironment() {
        String rconHost = System.getenv("MINECRAFT_RCON_HOST") != null
                ? System.getenv("MINECRAFT_RCON_HOST") : "localhost";

        int rconPort = System.getenv("MINECRAFT_RCON_PORT") != null
                ? Integer.parseInt(System.getenv("MINECRAFT_RCON_PORT")) : 25575;

        String rconPassword = System.getenv("MINECRAFT_RCON_PASSWORD") != null
                ? System.getenv("MINECRAFT_RCON_PASSWORD") : "";

        String serverStartCmd = System.getenv("MINECRAFT_START_COMMAND") != null
                ? System.getenv("MINECRAFT_START_COMMAND") : "systemctl start minecraft";

        String serverStopCmd = System.getenv("MINECRAFT_STOP_COMMAND") != null
                ? System.getenv("MINECRAFT_STOP_COMMAND") : "systemctl stop minecraft";

        String serverRestartCmd = System.getenv("MINECRAFT_RESTART_COMMAND") != null
                ? System.getenv("MINECRAFT_RESTART_COMMAND") : "systemctl restart minecraft";

        return new MinecraftServerManager(
                rconHost, rconPort, rconPassword,
                serverStartCmd, serverStopCmd, serverRestartCmd
        );
    }

    /**
     * サーバーにコマンドを実行
     *
     * @param command 実行するコマンド
     * @return コマンド実行結果
     * @throws IOException 通信エラーが発生した場合
     */
    public String executeCommand(String command) throws IOException {
        try (RconClient rcon = new RconClient(rconHost, rconPort, rconPassword)) {
            if (!rcon.connect()) {
                throw new IOException("RCONサーバーに接続できませんでした");
            }

            logger.info("コマンドを実行します: {}", command);
            return rcon.executeCommand(command);
        }
    }

    /**
     * サーバーを起動
     *
     * @return 成功時true
     */
    public boolean startServer() {
        try {
            logger.info("サーバーを起動しています");
            Process process = executeSystemCommand(serverStartCommand);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("サーバー起動コマンドを正常に実行しました");
                return true;
            } else {
                logger.error("サーバー起動コマンドが失敗しました。終了コード: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("サーバー起動中にエラーが発生しました", e);
            return false;
        }
    }

    /**
     * サーバーを停止
     *
     * @return 成功時true
     */
    public boolean stopServer() {
        try {
            // まず、RCONでsaveコマンドを実行
            try {
                executeCommand("save-all");
                logger.info("ワールドの保存を実行しました");
                // 少し待機してセーブが完了するのを待つ
                Thread.sleep(2000);
            } catch (Exception e) {
                logger.warn("ワールドの保存中にエラーが発生しました", e);
                // 続行（サーバーがすでに停止している可能性がある）
            }

            // 次に、RCONでstopコマンドを試す（失敗してもOK）
            try {
                executeCommand("stop");
                logger.info("サーバー停止コマンドをゲーム内で実行しました");
                // サーバーが停止するまで少し待機
                Thread.sleep(5000);
            } catch (Exception e) {
                logger.warn("ゲーム内停止コマンドの実行中にエラーが発生しました", e);
                // 続行（システムコマンドでの停止にフォールバック）
            }

            // システムコマンドでの停止
            logger.info("サーバーを停止しています");
            Process process = executeSystemCommand(serverStopCommand);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("サーバー停止コマンドを正常に実行しました");
                return true;
            } else {
                logger.error("サーバー停止コマンドが失敗しました。終了コード: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("サーバー停止中にエラーが発生しました", e);
            return false;
        }
    }

    /**
     * サーバーを再起動
     *
     * @return 成功時true
     */
    public boolean restartServer() {
        try {
            // まず、RCONでsaveコマンドを実行
            try {
                executeCommand("save-all");
                logger.info("ワールドの保存を実行しました");
                // 少し待機してセーブが完了するのを待つ
                Thread.sleep(2000);
            } catch (Exception e) {
                logger.warn("ワールドの保存中にエラーが発生しました", e);
                // 続行（サーバーがすでに停止している可能性がある）
            }

            // システムコマンドでの再起動
            logger.info("サーバーを再起動しています");
            Process process = executeSystemCommand(serverRestartCommand);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("サーバー再起動コマンドを正常に実行しました");
                return true;
            } else {
                logger.error("サーバー再起動コマンドが失敗しました。終了コード: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("サーバー再起動中にエラーが発生しました", e);
            return false;
        }
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
     * @param pluginName プラグイン名
     * @param enable 有効化する場合true、無効化する場合false
     * @return 成功時true
     */
    public boolean togglePlugin(String pluginName, boolean enable) {
        try {
            // プラグインコマンドを実行
            String command = enable ? "plugin enable " + pluginName : "plugin disable " + pluginName;
            String result = executeCommand(command);

            // 成功したかどうかを結果から判断
            boolean success = !result.toLowerCase().contains("error") && !result.toLowerCase().contains("unknown");

            if (success) {
                logger.info("プラグイン{}を{}しました: {}", pluginName, enable ? "有効化" : "無効化", result);
            } else {
                logger.warn("プラグイン{}の{}に失敗しました: {}", pluginName, enable ? "有効化" : "無効化", result);
            }

            return success;
        } catch (Exception e) {
            logger.error("プラグイン{}の{}中にエラーが発生しました", pluginName, enable ? "有効化" : "無効化", e);
            return false;
        }
    }

    /**
     * サーバーが現在動作しているかを確認
     *
     * @return サーバーが動作している場合true
     */
    public boolean isServerRunning() {
        try (RconClient rcon = new RconClient(rconHost, rconPort, rconPassword)) {
            return rcon.connect();
        } catch (Exception e) {
            logger.debug("サーバーが動作していないようです: {}", e.getMessage());
            return false;
        }
    }
}