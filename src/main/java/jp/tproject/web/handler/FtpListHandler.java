package jp.tproject.web.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import jp.tproject.core.AppException;
import jp.tproject.core.JsonUtil;
import jp.tproject.ftp.FtpManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FTPファイル一覧を取得するハンドラ
 */
public class FtpListHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(FtpListHandler.class);
    private final FtpManager ftpManager;

    /**
     * FtpListHandlerのインスタンスを作成
     *
     * @param ftpManager FTPマネージャー
     */
    public FtpListHandler(FtpManager ftpManager) {
        this.ftpManager = ftpManager;
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

        try {
            // クエリパラメータからディレクトリパスを取得
            String query = exchange.getRequestURI().getQuery();
            String directory = null;

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

            // FTPファイル一覧を取得
            List<Map<String, Object>> files = ftpManager.listFiles(directory);

            // レスポンスデータを作成
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("directory", directory != null ? directory : "/");
            responseData.put("files", files);

            sendJsonResponse(exchange, 200, responseData);
        } catch (AppException e) {
            logger.warn("FTPファイル一覧の取得に失敗しました: {}", e.getMessage());

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "FTPエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, e.getStatusCode(), errorData);
        } catch (Exception e) {
            logger.error("FTPファイル一覧の取得中にエラーが発生しました", e);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("success", false);
            errorData.put("error", "サーバーエラー");
            errorData.put("message", e.getMessage());

            sendJsonResponse(exchange, 500, errorData);
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