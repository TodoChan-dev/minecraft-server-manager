package jp.tproject.core;

/**
 * アプリケーション固有の例外クラス
 */
public class AppException extends RuntimeException {

    private final int statusCode;

    /**
     * デフォルトのステータスコード(500)でAppExceptionを作成
     *
     * @param message エラーメッセージ
     */
    public AppException(String message) {
        this(message, 500);
    }

    /**
     * 指定したステータスコードでAppExceptionを作成
     *
     * @param message エラーメッセージ
     * @param statusCode HTTPステータスコード
     */
    public AppException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * 原因となる例外とデフォルトのステータスコード(500)でAppExceptionを作成
     *
     * @param message エラーメッセージ
     * @param cause 原因となる例外
     */
    public AppException(String message, Throwable cause) {
        this(message, cause, 500);
    }

    /**
     * 原因となる例外と指定したステータスコードでAppExceptionを作成
     *
     * @param message エラーメッセージ
     * @param cause 原因となる例外
     * @param statusCode HTTPステータスコード
     */
    public AppException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * HTTPステータスコードを取得
     *
     * @return HTTPステータスコード
     */
    public int getStatusCode() {
        return statusCode;
    }
}