package jp.tproject.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON操作のためのユーティリティクラス
 */
public class JsonUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        // 整形されたJSON出力を有効化
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * オブジェクトをJSON文字列に変換
     *
     * @param obj 変換対象のオブジェクト
     * @return JSON文字列
     */
    public static String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new AppException("JSONへの変換に失敗しました", e);
        }
    }

    /**
     * JSON文字列を指定した型のオブジェクトに変換
     *
     * @param json JSON文字列
     * @param valueType 変換先の型
     * @param <T> 変換先の型パラメータ
     * @return 変換されたオブジェクト
     */
    public static <T> T fromJson(String json, Class<T> valueType) {
        try {
            return mapper.readValue(json, valueType);
        } catch (JsonProcessingException e) {
            throw new AppException("JSONからの変換に失敗しました", e);
        }
    }

    /**
     * InputStreamからJSON文字列を読み取り、指定した型のオブジェクトに変換
     *
     * @param is 入力ストリーム
     * @param valueType 変換先の型
     * @param <T> 変換先の型パラメータ
     * @return 変換されたオブジェクト
     */
    public static <T> T fromJson(InputStream is, Class<T> valueType) {
        try {
            return mapper.readValue(is, valueType);
        } catch (IOException e) {
            throw new AppException("JSONからの変換に失敗しました", e);
        }
    }

    /**
     * JSON文字列をMap<String, Object>に変換
     *
     * @param json JSON文字列
     * @return 変換されたMap
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> jsonToMap(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new AppException("JSONからMapへの変換に失敗しました", e);
        }
    }

    /**
     * InputStreamからJSON文字列を読み取り、バイト配列に変換
     *
     * @param is 入力ストリーム
     * @return 読み取られたバイト配列
     */
    public static byte[] readAllBytes(InputStream is) {
        try {
            return is.readAllBytes();
        } catch (IOException e) {
            throw new AppException("ストリームの読み取りに失敗しました", e);
        }
    }

    /**
     * InputStreamから読み取ったバイト配列をUTF-8文字列に変換
     *
     * @param is 入力ストリーム
     * @return UTF-8文字列
     */
    public static String readString(InputStream is) {
        return new String(readAllBytes(is), StandardCharsets.UTF_8);
    }

    /**
     * ObjectMapperのインスタンスを取得
     *
     * @return ObjectMapperのインスタンス
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }
}