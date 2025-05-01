package jp.tproject.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jp.tproject.core.AppException;
import jp.tproject.core.JsonUtil;
import jp.tproject.ftp.FtpManager;
import jp.tproject.ws.NotificationServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FTPファイルアップロードを処理するハンドラ
 * 複数サーバー対応
 */
public class FtpUploadHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(FtpUploadHandler.class);
    private final FtpManager ftpManager;
    private final NotificationServer notificationServer;
    private final String serverId;

    // multipart/form-dataの境界パターン
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("boundary=(.+)$");

    // 一時ファイル保存用ディレクトリ
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

    /**
     * FtpUploadHandlerのインスタンスを作成
     *
     * @param ftpManager FTPマネージャー
     * @param notificationServer 通知サーバー
     * @param serverId サーバーID
     */
    public FtpUploadHandler(FtpManager ftpManager, NotificationServer notificationServer, String serverId) {
        this.ftpManager = ftpManager;
        this.notificationServer = notificationServer;
        this.serverId = serverId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORSヘッダーを設定
        setCorsHeaders(exchange);

        // OPTIONSリクエストの場合はCORSプリフライトリクエストとして処理
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            handleCorsPreflightRequest(exchange);
            return;
        }

        // POSTリクエストのみを許可
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        // クエリパラメータからディレクトリパスを取得
        String query = exchange.getRequestURI().getQuery();
        String directory = "";

        if (query != null && !query.isEmpty()) {
            String[] params = query.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2 && keyValue[0].equals("path")) {
                    directory = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    break;
                }
            }
        }

        File tempFile = null;
        try {
            // Content-Typeヘッダーを確認
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                throw new AppException("Content-Typeはmultipart/form-dataである必要があります", 400);
            }

            // multipart/form-dataの境界を取得
            Matcher matcher = BOUNDARY_PATTERN.matcher(contentType);
            if (!matcher.find()) {
                throw new AppException("multipart boundaryが見つかりません", 400);
            }
            String boundary = matcher.group(1);

            // リクエストデータを解析
            Map<String, String> formFields = new HashMap<>();
            Map<String, byte[]> fileData = new HashMap<>();
            parseMultipartFormData(exchange.getRequestBody(), boundary, formFields, fileData);

            // ファイル名を取得
            String fileName = formFields.get("filename");
            if (fileName == null || fileName.isEmpty()) {
                throw new AppException("ファイル名が指定されていません", 400);
            }

            // ファイルデータを取得
            byte[] fileBytes = fileData.get("file");
            if (fileBytes == null || fileBytes.length == 0) {
                throw new AppException("ファイルデータが存在しません", 400);
            }

            // 一時ファイルを作成
            tempFile = File.createTempFile("upload_", ".tmp", new File(TEMP_DIR));
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(fileBytes);
            }

            // FTPサーバーにアップロード
            try (FileInputStream fis = new FileInputStream(tempFile)) {
                boolean success = ftpManager.uploadFile(directory, fileName, fis);

                if (success) {
                    logger.info("サーバー {} のファイルをアップロードしました: {}/{}", serverId, directory, fileName);

                    // WebSocket通知を送信
                    notificationServer.notifyFileOperation(serverId, "upload", directory + "/" + fileName, true);

                    // 成功レスポンスを送信
                    Map<String, Object> responseData = new HashMap<>();
                    responseData.put("success", true);
                    responseData.put("message", "ファイルをアップロードしました");
                    responseData.put("path", directory + "/" + fileName);
                    responseData.put("serverId", serverId);

                    sendJsonResponse(exchange, 200, responseData);
                } else {
                    throw new AppException("ファイルのアップロードに失敗しました", 500);
                }
            }
        } catch (AppException e) {
            logger.warn("サーバー {} のFTPファイルアップロードエラー: {}", serverId, e.getMessage());

            // WebSocket通知を送信
            String filename = exchange.getRequestHeaders().getFirst("X-Filename");
            if (filename == null) {
                filename = "unknown";
            }

            notificationServer.notifyFileOperation(serverId, "upload",
                    directory + "/" + filename, false);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "アップロードエラー");
            errorData.put("message", e.getMessage());
            errorData.put("serverId", serverId);

            sendJsonResponse(exchange, e.getStatusCode(), errorData);
        } catch (Exception e) {
            logger.error("サーバー {} のFTPファイルアップロード中に予期しないエラーが発生しました", serverId, e);

            // WebSocket通知を送信
            String filename = exchange.getRequestHeaders().getFirst("X-Filename");
            if (filename == null) {
                filename = "unknown";
            }

            notificationServer.notifyFileOperation(serverId, "upload",
                    directory + "/" + filename, false);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());
            errorData.put("serverId", serverId);

            sendJsonResponse(exchange, 500, errorData);
        } finally {
            // 一時ファイルを削除
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * multipart/form-dataを解析
     *
     * @param inputStream 入力ストリーム
     * @param boundary 境界文字列
     * @param formFields フォームフィールド格納先
     * @param fileData ファイルデータ格納先
     * @throws IOException 入出力例外
     */
    private void parseMultipartFormData(InputStream inputStream, String boundary,
                                        Map<String, String> formFields,
                                        Map<String, byte[]> fileData) throws IOException {
        // バウンダリ文字列
        String boundaryLine = "--" + boundary;
        String endBoundaryLine = "--" + boundary + "--";

        // バッファリーダーを作成
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        String line;
        boolean inHeader = true;
        boolean isFile = false;
        String currentName = null;
        StringBuilder headerBuilder = new StringBuilder();
        ByteArrayOutputStream fileContent = new ByteArrayOutputStream();

        // バウンダリまでスキップ
        while ((line = reader.readLine()) != null) {
            if (line.equals(boundaryLine)) {
                break;
            }
        }

        // マルチパートデータの解析
        while ((line = reader.readLine()) != null) {
            if (line.equals(boundaryLine) || line.equals(endBoundaryLine)) {
                // 前のパートが完了した場合
                if (currentName != null) {
                    if (isFile) {
                        fileData.put(currentName, fileContent.toByteArray());
                    } else {
                        String fieldValue = fileContent.toString(StandardCharsets.UTF_8).trim();
                        formFields.put(currentName, fieldValue);
                    }
                }

                // 新しいパートを開始
                inHeader = true;
                isFile = false;
                currentName = null;
                headerBuilder = new StringBuilder();
                fileContent = new ByteArrayOutputStream();

                // 終了バウンダリの場合は終了
                if (line.equals(endBoundaryLine)) {
                    break;
                }

                continue;
            }

            if (inHeader) {
                // ヘッダー部分の処理
                if (line.isEmpty()) {
                    // ヘッダー部分の終了
                    inHeader = false;

                    // ヘッダーからname属性を抽出
                    String header = headerBuilder.toString();
                    Pattern namePattern = Pattern.compile("name=\"([^\"]+)\"");
                    Matcher nameMatcher = namePattern.matcher(header);
                    if (nameMatcher.find()) {
                        currentName = nameMatcher.group(1);
                    }

                    // Content-Disposition: form-data; name="file"; filename="example.txt"
                    Pattern filenamePattern = Pattern.compile("filename=\"([^\"]+)\"");
                    Matcher filenameMatcher = filenamePattern.matcher(header);
                    if (filenameMatcher.find()) {
                        isFile = true;
                        formFields.put("filename", filenameMatcher.group(1));
                    }
                } else {
                    // ヘッダー行を追加
                    headerBuilder.append(line).append("\n");
                }
            } else {
                // ボディ部分の処理
                fileContent.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * CORSプリフライトリクエストを処理
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void handleCorsPreflightRequest(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1); // 204 No Content
    }

    /**
     * JSONレスポンスを送信
     *
     * @param exchange HTTPExchange
     * @param statusCode HTTPステータスコード
     * @param data レスポンスデータ
     * @throws IOException 入出力例外
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) throws IOException {
        String response = JsonUtil.toJson(data);
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * 405 Method Not Allowed レスポンスを送信
     *
     * @param exchange HTTPExchange
     * @throws IOException 入出力例外
     */
    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("success", false);
        errorData.put("error", "Method Not Allowed");
        errorData.put("message", "POSTメソッドのみが許可されています");
        errorData.put("serverId", serverId);

        setCorsHeaders(exchange);
        sendJsonResponse(exchange, 405, errorData);
    }

    /**
     * CORSヘッダーを設定
     *
     * @param exchange HTTPExchange
     */
    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Filename");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }
}