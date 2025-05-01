package jp.tproject.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 単一のマインクラフトサーバー設定情報を保持するクラス
 */
public class ServerConfig {
    private String id;                    // サーバーID (一意)
    private String name;                  // サーバー名称
    private String rconHost;              // RCONホスト
    private int rconPort;                 // RCONポート
    private String rconPassword;          // RCONパスワード
    private String serverStartCommand;    // サーバー起動コマンド
    private String serverStopCommand;     // サーバー停止コマンド
    private String serverRestartCommand;  // サーバー再起動コマンド
    private String pluginsDirectory;      // プラグインディレクトリパス
    private Map<String, String> extraParams; // 追加パラメータ

    /**
     * デフォルトコンストラクタ
     */
    public ServerConfig() {
        this.extraParams = new HashMap<>();
    }

    /**
     * パラメータ付きコンストラクタ
     *
     * @param id サーバーID
     * @param name サーバー名称
     * @param rconHost RCONホスト
     * @param rconPort RCONポート
     * @param rconPassword RCONパスワード
     * @param serverStartCommand 起動コマンド
     * @param serverStopCommand 停止コマンド
     * @param serverRestartCommand 再起動コマンド
     * @param pluginsDirectory プラグインディレクトリ
     */
    public ServerConfig(String id, String name, String rconHost, int rconPort, String rconPassword,
                        String serverStartCommand, String serverStopCommand, String serverRestartCommand,
                        String pluginsDirectory) {
        this.id = id;
        this.name = name;
        this.rconHost = rconHost;
        this.rconPort = rconPort;
        this.rconPassword = rconPassword;
        this.serverStartCommand = serverStartCommand;
        this.serverStopCommand = serverStopCommand;
        this.serverRestartCommand = serverRestartCommand;
        this.pluginsDirectory = pluginsDirectory;
        this.extraParams = new HashMap<>();
    }

    /**
     * 環境変数から設定を作成 (後方互換性用)
     *
     * @param id サーバーID
     * @return ServerConfigインスタンス
     */
    public static ServerConfig fromEnvironment(String id) {
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

        String pluginsDir = System.getenv("MINECRAFT_PLUGINS_DIR") != null
                ? System.getenv("MINECRAFT_PLUGINS_DIR") : "./plugins";

        return new ServerConfig(
                id,
                "Default Server",
                rconHost,
                rconPort,
                rconPassword,
                serverStartCmd,
                serverStopCmd,
                serverRestartCmd,
                pluginsDir
        );
    }

    // Getter & Setter メソッド
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRconHost() {
        return rconHost;
    }

    public void setRconHost(String rconHost) {
        this.rconHost = rconHost;
    }

    public int getRconPort() {
        return rconPort;
    }

    public void setRconPort(int rconPort) {
        this.rconPort = rconPort;
    }

    public String getRconPassword() {
        return rconPassword;
    }

    public void setRconPassword(String rconPassword) {
        this.rconPassword = rconPassword;
    }

    public String getServerStartCommand() {
        return serverStartCommand;
    }

    public void setServerStartCommand(String serverStartCommand) {
        this.serverStartCommand = serverStartCommand;
    }

    public String getServerStopCommand() {
        return serverStopCommand;
    }

    public void setServerStopCommand(String serverStopCommand) {
        this.serverStopCommand = serverStopCommand;
    }

    public String getServerRestartCommand() {
        return serverRestartCommand;
    }

    public void setServerRestartCommand(String serverRestartCommand) {
        this.serverRestartCommand = serverRestartCommand;
    }

    public String getPluginsDirectory() {
        return pluginsDirectory;
    }

    public void setPluginsDirectory(String pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory;
    }

    public Map<String, String> getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(Map<String, String> extraParams) {
        this.extraParams = extraParams;
    }

    public void addExtraParam(String key, String value) {
        this.extraParams.put(key, value);
    }

    public String getExtraParam(String key) {
        return this.extraParams.get(key);
    }

    public String getExtraParam(String key, String defaultValue) {
        return this.extraParams.getOrDefault(key, defaultValue);
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", rconHost='" + rconHost + '\'' +
                ", rconPort=" + rconPort +
                ", pluginsDirectory='" + pluginsDirectory + '\'' +
                '}';
    }
}