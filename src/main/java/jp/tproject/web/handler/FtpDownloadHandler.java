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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * FTPファイルダウンロードを処理するハンドラ
 */
public class FtpDownloadHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(FtpDownloadHandler.class);
    private final FtpManager ftpManager;
    private final NotificationServer notificationServer;

    // 一時ファイル保存用ディレクトリ
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

    /**
     * FtpDownloadHandlerのインスタンスを作成
     *
     * @param ftpManager FTPマネージャー
     * @param notificationServer 通知サーバー
     */
    public FtpDownloadHandler(FtpManager ftpManager, NotificationServer notificationServer) {
        this.ftpManager = ftpManager;
        this.notificationServer = notificationServer;
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

        // GETリクエストのみを許可
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        // パスを取得
        String requestPath = exchange.getRequestURI().getPath();
        String filePath = requestPath.replaceFirst("^/ftp/download/?", "");

        if (filePath.isEmpty()) {
            sendBadRequest(exchange, "ファイルパスが指定されていません");
            return;
        }

        // URLデコード
        filePath = URLDecoder.decode(filePath, StandardCharsets.UTF_8);

        File tempFile = null;
        try {
            // 一時ファイルを作成
            tempFile = File.createTempFile("download_", ".tmp", new File(TEMP_DIR));

            // FTPからファイルをダウンロード
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                boolean success = ftpManager.downloadFile(filePath, fos);

                if (success) {
                    logger.info("ファイルをダウンロードしました: {}", filePath);

                    // WebSocket通知を送信
                    notificationServer.notifyFileOperation("download", filePath, true);

                    // ファイル名を取得
                    Path path = Paths.get(filePath);
                    String fileName = path.getFileName().toString();

                    // Content-Dispositionヘッダーを設定
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");

                    // ファイルサイズを取得
                    long fileSize = tempFile.length();
                    exchange.sendResponseHeaders(200, fileSize);

                    // ファイルを送信
                    byte[] buffer = new byte[4096];
                    int bytesRead;

                    try (FileInputStream inputStream = new FileInputStream(tempFile);
                         OutputStream os = exchange.getResponseBody()) {
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }

                    // レスポンスボディはすでに送信済みなので、ここでは閉じない
                } else {
                    throw new AppException("ファイルのダウンロードに失敗しました", 500);
                }
            }
        } catch (AppException e) {
            logger.warn("FTPファイルダウンロードエラー: {}", e.getMessage());

            // WebSocket通知を送信
            notificationServer.notifyFileOperation("download", filePath, false);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "ダウンロードエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, e.getStatusCode(), errorData);
        } catch (Exception e) {
            logger.error("FTPファイルダウンロード中に予期しないエラーが発生しました", e);

            // WebSocket通知を送信
            notificationServer.notifyFileOperation("download", filePath, false);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
        } finally {
            // 一時ファイルを削除
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
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
     * 400 Bad Request レスポンスを送信
     *
     * @param exchange HTTPExchange
     * @param message エラーメッセージ
     * @throws IOException 入出力例外
     */
    private void sendBadRequest(HttpExchange exchange, String message) throws IOException {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("success", false);
        errorData.put("error", "Bad Request");
        errorData.put("message", message);

        sendJsonResponse(exchange, 400, errorData);
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
        errorData.put("message", "GETメソッドのみが許可されています");

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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }
}