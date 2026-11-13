package cn.xinkelink.media;

import java.io.IOException;

/** 私有内容读取边界，与既有ImageStorage写入接口分开。 */
public interface MediaContentReader {
    byte[] read(String storageKey) throws IOException;
}
