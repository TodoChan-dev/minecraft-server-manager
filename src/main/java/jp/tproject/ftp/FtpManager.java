package jp.tproject.ftp;

import jp.tproject.core.AppException;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FTP操作を管理するクラス
 */
public class FtpManager {

    private static final Logger logger = LoggerFactory.getLogger(FtpManager.class);

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    /**
     * FtpManagerのインスタンスを作成
     *
     * @param host FTPサーバーのホスト名
     * @param port FTPサーバーのポート番号
     * @param username FTPサーバーのユーザー名
     * @param password FTPサーバーのパスワード
     */
    public FtpManager(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * 指定されたディレクトリのファイル一覧を取得
     *
     * @param directory 対象ディレクトリパス
     * @return ファイル情報のリスト
     */
    public List<Map<String, Object>> listFiles(String directory) {
        FTPClient ftpClient = new FTPClient();
        try {
            connectAndLogin(ftpClient);

            // ディレクトリの指定がない場合はルートディレクトリを使用
            String targetDir = directory == null || directory.isEmpty() ? "/" : directory;

            // ファイル一覧を取得
            FTPFile[] files = ftpClient.listFiles(targetDir);

            List<Map<String, Object>> result = new ArrayList<>();
            for (FTPFile file : files) {
                result.add(Map.of(
                        "name", file.getName(),
                        "size", file.getSize(),
                        "type", file.isDirectory() ? "directory" : "file",
                        "lastModified", file.getTimestamp().getTimeInMillis(),
                        "permissions", file.hasPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION)
                ));
            }

            return result;
        } catch (IOException e) {
            logger.error("FTPファイル一覧の取得に失敗しました", e);
            throw new AppException("FTPファイル一覧の取得に失敗しました: " + e.getMessage(), e);
        } finally {
            disconnectQuietly(ftpClient);
        }
    }

    /**
     * ファイルをFTPサーバーにアップロード
     *
     * @param directory アップロード先ディレクトリ
     * @param filename ファイル名
     * @param inputStream アップロードするファイルの入力ストリーム
     * @return アップロード成功時true
     */
    public boolean uploadFile(String directory, String filename, InputStream inputStream) {
        FTPClient ftpClient = new FTPClient();
        try {
            connectAndLogin(ftpClient);

            // ディレクトリの指定がない場合はルートディレクトリを使用
            String targetDir = directory == null || directory.isEmpty() ? "/" : directory;

            // 必要に応じてディレクトリを変更
            if (!targetDir.equals("/")) {
                ftpClient.changeWorkingDirectory(targetDir);
            }

            // ファイルをアップロード
            boolean success = ftpClient.storeFile(filename, inputStream);
            if (!success) {
                throw new AppException("ファイルのアップロードに失敗しました");
            }

            return true;
        } catch (IOException e) {
            logger.error("FTPファイルのアップロードに失敗しました", e);
            throw new AppException("FTPファイルのアップロードに失敗しました: " + e.getMessage(), e);
        } finally {
            disconnectQuietly(ftpClient);
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                logger.warn("入力ストリームのクローズに失敗しました", e);
            }
        }
    }

    /**
     * FTPサーバーからファイルをダウンロード
     *
     * @param filePath ダウンロードするファイルのパス
     * @param outputStream ダウンロードしたファイルを書き込む出力ストリーム
     * @return ダウンロード成功時true
     */
    public boolean downloadFile(String filePath, OutputStream outputStream) {
        FTPClient ftpClient = new FTPClient();
        try {
            connectAndLogin(ftpClient);

            // バイナリ転送モードを設定
            ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);

            // ファイルをダウンロード
            boolean success = ftpClient.retrieveFile(filePath, outputStream);
            if (!success) {
                throw new AppException("ファイルのダウンロードに失敗しました");
            }

            return true;
        } catch (IOException e) {
            logger.error("FTPファイルのダウンロードに失敗しました", e);
            throw new AppException("FTPファイルのダウンロードに失敗しました: " + e.getMessage(), e);
        } finally {
            disconnectQuietly(ftpClient);
        }
    }

    /**
     * FTPサーバーに接続してログイン
     *
     * @param ftpClient FTPクライアント
     * @throws IOException 入出力例外
     */
    private void connectAndLogin(FTPClient ftpClient) throws IOException {
        // サーバーに接続
        ftpClient.connect(host, port);

        // 接続の応答コードを確認
        int replyCode = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            ftpClient.disconnect();
            throw new AppException("FTPサーバーに接続できませんでした。応答コード: " + replyCode);
        }

        // ログイン
        boolean loggedIn = ftpClient.login(username, password);
        if (!loggedIn) {
            ftpClient.disconnect();
            throw new AppException("FTPサーバーへのログインに失敗しました");
        }

        // パッシブモードを設定（多くのファイアウォールと互換性がある）
        ftpClient.enterLocalPassiveMode();
    }

    /**
     * FTPクライアントを安全に切断
     *
     * @param ftpClient FTPクライアント
     */
    private void disconnectQuietly(FTPClient ftpClient) {
        if (ftpClient != null && ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (IOException e) {
                logger.warn("FTP切断中にエラーが発生しました", e);
            }
        }
    }
}