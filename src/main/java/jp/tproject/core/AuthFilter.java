package jp.tproject.core;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP認証フィルター
 * Discord JWTトークン検証を行う
 */
public class AuthFilter extends Filter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    // 環境変数から取得するか、設定ファイルから読み込む
    private static final String JWT_SECRET = System.getenv("JWT_SECRET");

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        // CORS対応のためOPTIONSリクエストは認証をスキップ
        if (exchange.getRequestMethod().equals("OPTIONS")) {
            chain.doFilter(exchange);
            return;
        }

        // 認証ヘッダーを取得
        List<String> authHeaders = exchange.getRequestHeaders().get(AUTH_HEADER);
        if (authHeaders == null || authHeaders.isEmpty()) {
            sendUnauthorized(exchange, "認証ヘッダーがありません");
            return;
        }

        String authHeader = authHeaders.get(0);
        if (!authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(exchange, "Bearer認証が必要です");
            return;
        }

        // トークン部分を取得
        String token = authHeader.substring(BEARER_PREFIX.length());
        try {
            // JWT検証（実際の実装はより堅牢に行う必要があります）
            validateJwt(token);

            // 認証成功したらチェーンを続行
            chain.doFilter(exchange);
        } catch (Exception e) {
            sendUnauthorized(exchange, "無効なトークンです: " + e.getMessage());
        }
    }

    /**
     * JWT検証処理
     * 実際の実装では適切なJWTライブラリを使用すること
     *
     * @param token 検証するJWTトークン
     */
    private void validateJwt(String token) {
        // TODO: 実際のJWT検証ロジックを実装
        // 例: io.jsonwebtoken:jjwt ライブラリを使用

        // 開発中は検証をスキップ（本番環境では必ず適切な検証を行うこと）
        if (JWT_SECRET == null || JWT_SECRET.isEmpty()) {
            // 開発モードと判断し、検証をスキップ
            return;
        }

        // 実際の検証処理
        // Jws<Claims> claims = Jwts.parser().setSigningKey(JWT_SECRET).parseClaimsJws(token);
        // 検証に失敗した場合は例外がスローされる
    }

    /**
     * 401 Unauthorized レスポンスを送信
     *
     * @param exchange HTTPExchange
     * @param message エラーメッセージ
     * @throws IOException 入出力例外
     */
    private void sendUnauthorized(HttpExchange exchange, String message) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");

        String response = JsonUtil.toJson(Map.of(
                "error", "unauthorized",
                "message", message
        ));

        exchange.sendResponseHeaders(401, response.getBytes().length);
        exchange.getResponseBody().write(response.getBytes());
        exchange.getResponseBody().close();
    }

    @Override
    public String description() {
        return "Discord JWT認証フィルター";
    }
}