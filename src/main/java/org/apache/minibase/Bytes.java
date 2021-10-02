package org.apache.minibase;

import java.io.IOException;

/** Byte工具类; */
public class Bytes {

  public static final byte[] EMPTY_BYTES = new byte[0];
  public static final String HEX_TMP = "0123456789ABCDEF";

  public static byte[] toBytes(byte b) {
    return new byte[] {b};
  }

  /**
   * String转byte数组;
   *
   * @param s 输入String数据;
   * @return byte数组;
   * @throws IOException
   */
  public static byte[] toBytes(String s) throws IOException {
    if (s == null) return new byte[0];
    return s.getBytes("UTF-8");
  }

  /**
   * int转byte数组;
   *
   * @param x 输入int数据;
   * @return byte数组;
   */
  public static byte[] toBytes(int x) {
    byte[] b = new byte[4];
    b[3] = (byte) (x & 0xFF);
    b[2] = (byte) ((x >> 8) & 0xFF);
    b[1] = (byte) ((x >> 16) & 0xFF);
    b[0] = (byte) ((x >> 24) & 0xFF);
    return b;
  }

  /**
   * long转byte数组;
   *
   * @param x 输入long数据;
   * @return byte数组;
   */
  public static byte[] toBytes(long x) {
    byte[] b = new byte[8];
    for (int i = 7; i >= 0; i--) {
      int j = (7 - i) << 3;
      b[i] = (byte) ((x >> j) & 0xFF);
    }
    return b;
  }

  /**
   * byte数组转化成String;
   *
   * @param buf 输入byte数组;
   * @return
   */
  public static String toHex(byte[] buf) {
    return toHex(buf, 0, buf.length);
  }

  /**
   * 将byte数组指定的位置数据转化成String;
   *
   * @param buf 输入byte数组;
   * @param offset 开始读取的位置;
   * @param len 读取的长度;
   * @return 转化成的String数据;
   */
  public static String toHex(byte[] buf, int offset, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = offset; i < offset + len; i++) {
      int x = buf[i];
      if (x > 32 && x < 127) {
        sb.append((char) x);
      } else {
        sb.append("\\x").append(HEX_TMP.charAt((x >> 4) & 0x0F)).append(HEX_TMP.charAt(x & 0x0F));
      }
    }
    return sb.toString();
  }

  /**
   * 合并两个byte数组;
   *
   * @param a byte数组1;
   * @param b byte数组2;
   * @return 合并后的byte数组;
   */
  public static byte[] toBytes(byte[] a, byte[] b) {
    if (a == null) return b;
    if (b == null) return a;
    byte[] result = new byte[a.length + b.length];
    System.arraycopy(a, 0, result, 0, a.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }

  /**
   * byte数组转化成Int类型;
   *
   * @param a 输入的byte数组;
   * @return 转化成的Int数据;
   */
  public static int toInt(byte[] a) {
    return (a[0] << 24) & 0xFF000000
        | (a[1] << 16) & 0x00FF0000
        | (a[2] << 8) & 0x0000FF00
        | (a[3] << 0) & 0x000000FF;
  }

  /**
   * byte数组转化成Long类型;
   *
   * @param a 输入的byte数组;
   * @return 转化成的Long数据;
   */
  public static long toLong(byte[] a) {
    long x = 0;
    for (int i = 0; i < 8; i++) {
      int j = (7 - i) << 3;
      x |= ((0xFFL << j) & ((long) a[i] << j));
    }
    return x;
  }

  /**
   * 提取指定位置的数据; 将buf从offset位置开始,提取len长度的数据,并返回;
   *
   * @param buf
   * @param offset
   * @param len
   * @return
   * @throws IOException
   */
  public static byte[] slice(byte[] buf, int offset, int len) throws IOException {
    if (buf == null) {
      throw new IOException("buffer is null");
    }
    if (offset < 0 || len < 0) {
      throw new IOException("Invalid offset: " + offset + " or len: " + len);
    }
    if (offset + len > buf.length) {
      throw new IOException(
          "Buffer overflow, offset: " + offset + ", len: " + len + ", buf.length:" + buf.length);
    }
    byte[] result = new byte[len];
    System.arraycopy(buf, offset, result, 0, len);
    return result;
  }

  /**
   * 对key进行hash;
   *
   * @param key
   * @return
   */
  public static int hash(byte[] key) {
    if (key == null) return 0;
    int h = 1;
    for (int i = 0; i < key.length; i++) {
      h = (h << 5) + h + key[i];
    }
    return h;
  }

  /**
   * 比较两个byte数组是否相等相同;
   *
   * @param a
   * @param b
   * @return
   */
  public static int compare(byte[] a, byte[] b) {
    if (a == b) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    for (int i = 0, j = 0; i < a.length && j < b.length; i++, j++) {
      int x = a[i] & 0xFF;
      int y = b[i] & 0xFF;
      if (x != y) {
        return x - y;
      }
    }
    return a.length - b.length;
  }
}
