package jp.tproject.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 設定ファイルを管理するクラス
 * settings/ディレクトリ内のYAMLファイルから設定を読み込む
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    // 設定ディレクトリ
    private static final String SETTINGS_DIR = "settings";
    // メイン設定ファイル名
    private static final String MAIN_CONFIG_FILE = "config.yml";

    // シングルトンインスタンス
    private static ConfigManager instance;

    // サーバー設定マップ (ID -> ServerConfig)
    private Map<String, ServerConfig> serverConfigs = new HashMap<>();
    // グローバル設定マップ
    private Map<String, Object> globalConfig = new HashMap<>();

    /**
     * コンストラクタ - 設定ファイルを読み込む
     */
    private ConfigManager() {
        initializeSettingsDirectory();
        loadAllConfigs();
    }

    /**
     * シングルトンインスタンスを取得
     *
     * @return ConfigManagerインスタンス
     */
    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /**
     * 設定ディレクトリを初期化
     */
    private void initializeSettingsDirectory() {
        Path settingsPath = Paths.get(SETTINGS_DIR);
        try {
            if (!Files.exists(settingsPath)) {
                Files.createDirectory(settingsPath);
                logger.info("設定ディレクトリを作成しました: {}", settingsPath.toAbsolutePath());
                createDefaultConfigFile(settingsPath);
            }
        } catch (IOException e) {
            logger.error("設定ディレクトリの初期化に失敗しました", e);
        }
    }

    /**
     * デフォルト設定ファイルを作成
     *
     * @param settingsPath 設定ディレクトリのパス
     * @throws IOException ファイル作成に失敗した場合
     */
    private void createDefaultConfigFile(Path settingsPath) throws IOException {
        Path configFile = settingsPath.resolve(MAIN_CONFIG_FILE);

        // デフォルト設定をYAML形式で作成
        String defaultConfig = "# メインサーバー設定ファイル\n" +
                "global:\n" +
                "  web_port: 3031\n" +
                "  websocket_port: 3032\n" +
                "  default_server: main\n" +
                "\n" +
                "# 初期設定サーバー\n" +
                "servers:\n" +
                "  main:\n" +
                "    name: メインサーバー\n" +
                "    rcon_host: localhost\n" +
                "    rcon_port: 25575\n" +
                "    rcon_password: password\n" +
                "    start_command: systemctl start minecraft\n" +
                "    stop_command: systemctl stop minecraft\n" +
                "    restart_command: systemctl restart minecraft\n" +
                "    plugins_directory: ./plugins\n" +
                "\n" +
                "# サーバー追加例\n" +
                "# servers:\n" +
                "#   survival:\n" +
                "#     name: サバイバルサーバー\n" +
                "#     rcon_host: localhost\n" +
                "#     rcon_port: 25576\n" +
                "#     rcon_password: password\n" +
                "#     ...\n";

        Files.writeString(configFile, defaultConfig);
        logger.info("デフォルト設定ファイルを作成しました: {}", configFile.toAbsolutePath());
    }

    /**
     * すべての設定ファイルを読み込む
     */
    public void loadAllConfigs() {
        // 設定マップをクリア
        serverConfigs.clear();
        globalConfig.clear();

        // メイン設定ファイルを読み込む
        Path mainConfigPath = Paths.get(SETTINGS_DIR, MAIN_CONFIG_FILE);
        if (Files.exists(mainConfigPath)) {
            loadMainConfig(mainConfigPath.toFile());
        } else {
            logger.warn("メイン設定ファイルが見つかりません: {}", mainConfigPath);
            // デフォルトサーバーを追加（後方互換性のため）
            serverConfigs.put("default", ServerConfig.fromEnvironment("default"));
        }

        // settings/*_server.yml 形式のファイルも読み込む
        try {
            List<Path> serverConfigFiles = Files.list(Paths.get(SETTINGS_DIR))
                    .filter(path -> path.getFileName().toString().endsWith("_server.yml"))
                    .collect(Collectors.toList());

            for (Path configFile : serverConfigFiles) {
                try {
                    loadServerConfig(configFile.toFile());
                } catch (Exception e) {
                    logger.error("サーバー設定ファイルの読み込みに失敗しました: {}", configFile, e);
                }
            }
        } catch (IOException e) {
            logger.error("サーバー設定ファイルの検索に失敗しました", e);
        }

        logger.info("設定読み込み完了 - サーバー数: {}", serverConfigs.size());
        for (ServerConfig config : serverConfigs.values()) {
            logger.info(" - サーバー: {} ({})", config.getName(), config.getId());
        }
    }

    /**
     * メイン設定ファイルを読み込む
     *
     * @param configFile 設定ファイル
     */
    @SuppressWarnings("unchecked")
    private void loadMainConfig(File configFile) {
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(fis);

            // グローバル設定を読み込む
            if (config.containsKey("global")) {
                globalConfig = (Map<String, Object>) config.get("global");
            }

            // サーバー設定を読み込む
            if (config.containsKey("servers")) {
                Map<String, Map<String, Object>> serversConfig = (Map<String, Map<String, Object>>) config.get("servers");

                for (Map.Entry<String, Map<String, Object>> entry : serversConfig.entrySet()) {
                    String serverId = entry.getKey();
                    Map<String, Object> serverData = entry.getValue();

                    ServerConfig serverConfig = new ServerConfig();
                    serverConfig.setId(serverId);

                    if (serverData.containsKey("name")) {
                        serverConfig.setName((String) serverData.get("name"));
                    } else {
                        serverConfig.setName(serverId);
                    }

                    serverConfig.setRconHost((String) serverData.getOrDefault("rcon_host", "localhost"));

                    Object rconPortObj = serverData.getOrDefault("rcon_port", 25575);
                    int rconPort = rconPortObj instanceof Integer ? (Integer) rconPortObj :
                            Integer.parseInt(rconPortObj.toString());
                    serverConfig.setRconPort(rconPort);

                    serverConfig.setRconPassword((String) serverData.getOrDefault("rcon_password", ""));
                    serverConfig.setServerStartCommand((String) serverData.getOrDefault("start_command", ""));
                    serverConfig.setServerStopCommand((String) serverData.getOrDefault("stop_command", ""));
                    serverConfig.setServerRestartCommand((String) serverData.getOrDefault("restart_command", ""));
                    serverConfig.setPluginsDirectory((String) serverData.getOrDefault("plugins_directory", "./plugins"));

                    // 追加パラメータを処理
                    for (Map.Entry<String, Object> param : serverData.entrySet()) {
                        String key = param.getKey();
                        // 基本プロパティはスキップ
                        if (!List.of("name", "rcon_host", "rcon_port", "rcon_password",
                                "start_command", "stop_command", "restart_command",
                                "plugins_directory").contains(key)) {
                            serverConfig.addExtraParam(key, param.getValue().toString());
                        }
                    }

                    serverConfigs.put(serverId, serverConfig);
                    logger.info("サーバー設定を読み込みました: {} ({})", serverConfig.getName(), serverId);
                }
            }
        } catch (Exception e) {
            logger.error("メイン設定ファイルの読み込みに失敗しました: {}", configFile, e);
        }
    }

    /**
     * 個別のサーバー設定ファイルを読み込む (xxx_server.yml)
     *
     * @param configFile 設定ファイル
     */
    @SuppressWarnings("unchecked")
    private void loadServerConfig(File configFile) {
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> serverData = yaml.load(fis);

            // ファイル名からサーバーIDを抽出
            String fileName = configFile.getName();
            String serverId = fileName.substring(0, fileName.length() - "_server.yml".length());

            ServerConfig serverConfig = new ServerConfig();
            serverConfig.setId(serverId);

            if (serverData.containsKey("name")) {
                serverConfig.setName((String) serverData.get("name"));
            } else {
                serverConfig.setName(serverId);
            }

            serverConfig.setRconHost((String) serverData.getOrDefault("rcon_host", "localhost"));

            Object rconPortObj = serverData.getOrDefault("rcon_port", 25575);
            int rconPort = rconPortObj instanceof Integer ? (Integer) rconPortObj :
                    Integer.parseInt(rconPortObj.toString());
            serverConfig.setRconPort(rconPort);

            serverConfig.setRconPassword((String) serverData.getOrDefault("rcon_password", ""));
            serverConfig.setServerStartCommand((String) serverData.getOrDefault("start_command", ""));
            serverConfig.setServerStopCommand((String) serverData.getOrDefault("stop_command", ""));
            serverConfig.setServerRestartCommand((String) serverData.getOrDefault("restart_command", ""));
            serverConfig.setPluginsDirectory((String) serverData.getOrDefault("plugins_directory", "./plugins"));

            // 追加パラメータを処理
            for (Map.Entry<String, Object> param : serverData.entrySet()) {
                String key = param.getKey();
                // 基本プロパティはスキップ
                if (!List.of("name", "rcon_host", "rcon_port", "rcon_password",
                        "start_command", "stop_command", "restart_command",
                        "plugins_directory").contains(key)) {
                    serverConfig.addExtraParam(key, param.getValue().toString());
                }
            }

            serverConfigs.put(serverId, serverConfig);
            logger.info("サーバー設定ファイルを読み込みました: {} ({})", fileName, serverId);
        } catch (Exception e) {
            logger.error("サーバー設定ファイルの読み込みに失敗しました: {}", configFile, e);
        }
    }

    /**
     * 指定されたIDのサーバー設定を取得
     *
     * @param serverId サーバーID
     * @return ServerConfigインスタンス (見つからない場合はnull)
     */
    public ServerConfig getServerConfig(String serverId) {
        return serverConfigs.get(serverId);
    }

    /**
     * デフォルトサーバーの設定を取得
     *
     * @return ServerConfigインスタンス
     */
    public ServerConfig getDefaultServerConfig() {
        // グローバル設定からデフォルトサーバーIDを取得
        String defaultServerId = (String) globalConfig.getOrDefault("default_server", "main");

        // デフォルトサーバーが存在しない場合は最初のサーバーを返す
        if (!serverConfigs.containsKey(defaultServerId)) {
            if (serverConfigs.isEmpty()) {
                // サーバーが一つもない場合はデフォルト設定を作成
                ServerConfig defaultConfig = ServerConfig.fromEnvironment("default");
                serverConfigs.put("default", defaultConfig);
                return defaultConfig;
            } else {
                return serverConfigs.values().iterator().next();
            }
        }

        return serverConfigs.get(defaultServerId);
    }

    /**
     * すべてのサーバー設定を取得
     *
     * @return ServerConfigのMap
     */
    public Map<String, ServerConfig> getAllServerConfigs() {
        return Collections.unmodifiableMap(serverConfigs);
    }

    /**
     * グローバル設定値を取得
     *
     * @param key 設定キー
     * @param defaultValue デフォルト値
     * @return 設定値
     */
    public Object getGlobalConfig(String key, Object defaultValue) {
        return globalConfig.getOrDefault(key, defaultValue);
    }

    /**
     * Webサーバーポート番号を取得
     *
     * @return ポート番号
     */
    public int getWebPort() {
        Object port = getGlobalConfig("web_port", 3031);
        if (port instanceof Integer) {
            return (Integer) port;
        } else {
            return Integer.parseInt(port.toString());
        }
    }

    /**
     * WebSocketサーバーポート番号を取得
     *
     * @return ポート番号
     */
    public int getWebSocketPort() {
        Object port = getGlobalConfig("websocket_port", 3032);
        if (port instanceof Integer) {
            return (Integer) port;
        } else {
            return Integer.parseInt(port.toString());
        }
    }
}