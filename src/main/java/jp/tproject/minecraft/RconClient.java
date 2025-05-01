package jp.tproject.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * MinecraftサーバーのRCONプロトコルを使用してコマンドを実行するクライアント
 */
public class RconClient implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(RconClient.class);

    private static final int PACKET_TYPE_AUTH = 3;
    private static final int PACKET_TYPE_COMMAND = 2;
    private static final int PACKET_TYPE_RESPONSE = 0;

    private final String host;
    private final int port;
    private final String password;

    private Socket socket;
    private int requestId;
    private boolean authenticated;

    /**
     * RCONクライアントを初期化
     *
     * @param host RCONホスト
     * @param port RCONポート
     * @param password RCONパスワード
     */
    public RconClient(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.requestId = 1;
        this.authenticated = false;
    }

    /**
     * RCONサーバーに接続して認証を行う
     *
     * @return 認証成功時true
     * @throws IOException 接続や認証に失敗した場合
     */
    public boolean connect() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return authenticated;
        }

        try {
            socket = new Socket(host, port);

            // 認証パケットを送信
            int authId = sendPacket(PACKET_TYPE_AUTH, password);

            // 認証レスポンスを受信
            ByteBuffer response = receivePacket();
            int responseId = response.getInt();

            // 認証成功を確認（レスポンスIDが正しいかどうか）
            authenticated = (responseId == authId);

            if (!authenticated) {
                logger.warn("RCON認証に失敗しました: {}", host);
                close();
            } else {
                logger.info("RCON接続に成功しました: {}", host);
            }

            return authenticated;
        } catch (IOException e) {
            logger.error("RCON接続エラー: {}", e.getMessage());
            close();
            throw e;
        }
    }

    /**
     * クライアントが接続されているかどうかを確認
     *
     * @return 接続されていてかつ認証済みの場合true
     */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && authenticated;
    }

    /**
     * コマンドを実行してレスポンスを取得
     *
     * @param command 実行するコマンド
     * @return コマンド実行結果
     * @throws IOException 通信エラーが発生した場合
     */
    public String executeCommand(String command) throws IOException {
        if (!authenticated) {
            if (!connect()) {
                throw new IOException("RCON認証に失敗しました");
            }
        }

        try {
            // コマンドパケットを送信
            int cmdId = sendPacket(PACKET_TYPE_COMMAND, command);

            // レスポンスを受信
            ByteBuffer response = receivePacket();
            int responseId = response.getInt();

            // レスポンスIDが一致するか確認
            if (responseId != cmdId) {
                throw new IOException("不正なRCONレスポンスID: " + responseId + " != " + cmdId);
            }

            // レスポンスのタイプを確認
            int responseType = response.getInt();
            if (responseType != PACKET_TYPE_RESPONSE) {
                throw new IOException("不正なRCONレスポンスタイプ: " + responseType);
            }

            // レスポンスボディを読み取り
            byte[] bodyBytes = new byte[response.remaining() - 2]; // 2バイトの終端NULLを除く
            response.get(bodyBytes);

            // 末尾の2バイトのNULLをスキップ
            response.position(response.position() + 2);

            return new String(bodyBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("RCONコマンド実行エラー: {}", e.getMessage());
            // 接続エラーの場合は接続を閉じて再接続を試みる
            authenticated = false;
            close();
            throw e;
        }
    }

    /**
     * RCONパケットを送信
     *
     * @param type パケットタイプ
     * @param payload パケットペイロード
     * @return リクエストID
     * @throws IOException 送信エラーが発生した場合
     */
    private int sendPacket(int type, String payload) throws IOException {
        int id = requestId++;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // パケットの長さ = ペイロード長 + 14 (4バイトのID + 4バイトのタイプ + 終端の2つのNULL)
        int length = payloadBytes.length + 10;

        ByteBuffer packet = ByteBuffer.allocate(length + 4);
        packet.order(ByteOrder.LITTLE_ENDIAN);

        // パケットサイズ（ヘッダーを除く）
        packet.putInt(length);
        // リクエストID
        packet.putInt(id);
        // パケットタイプ
        packet.putInt(type);
        // ペイロード
        packet.put(payloadBytes);
        // 終端のNULL
        packet.put((byte) 0);
        packet.put((byte) 0);

        // パケットを送信
        packet.flip();
        socket.getOutputStream().write(packet.array());

        return id;
    }

    /**
     * RCONパケットを受信
     *
     * @return 受信したパケットのバッファ
     * @throws IOException 受信エラーが発生した場合
     */
    private ByteBuffer receivePacket() throws IOException {
        // まずパケットの長さを取得（4バイト）
        byte[] lengthBytes = new byte[4];
        int read = socket.getInputStream().read(lengthBytes);
        if (read != 4) {
            throw new IOException("RCONパケット長の読み取りに失敗しました: " + read);
        }

        ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthBytes);
        lengthBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int length = lengthBuffer.getInt();

        // パケットの内容を読み取り
        byte[] packetBytes = new byte[length];
        read = socket.getInputStream().read(packetBytes);
        if (read != length) {
            throw new IOException("RCONパケット本体の読み取りに失敗しました: " + read + " != " + length);
        }

        ByteBuffer packetBuffer = ByteBuffer.wrap(packetBytes);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);

        return packetBuffer;
    }

    /**
     * RCONクライアントを閉じる
     */
    @Override
    public void close() {
        authenticated = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                logger.warn("RCON接続のクローズ中にエラーが発生しました: {}", e.getMessage());
            }
            socket = null;
        }
    }

    /**
     * インスタンスがガベージコレクションされる前に接続を閉じる
     */
    @Override
    @SuppressWarnings("removal")
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}