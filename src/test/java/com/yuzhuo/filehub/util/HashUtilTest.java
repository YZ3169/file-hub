package com.yuzhuo.filehub.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 哈希是整个项目的信任根——秒传靠它判断"内容相同"，合并后的完整性校验也靠它。
 * 所以这里用的是 NIST 公布的公开测试向量，而不是"跑一遍看看输出是什么"。
 */
class HashUtilTest {

    @Test
    @DisplayName("SHA-256 对空输入的标准向量")
    void emptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HashUtil.sha256Hex(new byte[0]));
    }

    @Test
    @DisplayName("SHA-256(\"abc\") 的标准向量")
    void abcVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("448 位标准向量（正好一个数据块）")
    void multiBlockVector() {
        String s = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                HashUtil.sha256Hex(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("流式与一次性计算结果一致；大输入也不炸内存")
    void streamingMatchesByteArray() throws IOException {
        byte[] big = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(big);

        String once = HashUtil.sha256Hex(big);
        String streamed = HashUtil.sha256Hex(new ByteArrayInputStream(big));

        assertEquals(once, streamed);
    }

    @Test
    @DisplayName("只差一个字节，摘要必须完全不同")
    void avalanche() {
        byte[] a = new byte[1024];
        byte[] b = new byte[1024];
        new Random(7).nextBytes(a);
        System.arraycopy(a, 0, b, 0, a.length);
        b[1023] ^= 0x01;

        assertNotEquals(HashUtil.sha256Hex(a), HashUtil.sha256Hex(b));
    }
}
