package dev.codex.watchtheme;

import hct.Hct;
import java.util.Map;
import quantize.QuantizerCelebi;
import score.Score;

/**
 * 表盘主题调色板：基于官方 Material Color Utilities（MCU）离线源码派生。
 *
 * <p>输入固定为 {@code fromPixels} 的 ARGB 像素或 {@code fromSeed} 的单一 ARGB 种子色。取色路径为
 * QuantizerCelebi（maxColors=64）量化后用 Score(desired=1, fallback, filter=true) 选出种子色，再由
 * 种子的 HCT 色相派生强调色与中性色两套色阶。类本身无状态、无 Android 依赖，可在纯 JVM 中离线测试。
 */
public final class ThemePalette {

  /** 强调色（accent）色度，决定主色饱和程度。 */
  private static final double ACCENT_CHROMA = 36.0;
  /** 中性色（neutral）色度，保持选中/轨道底色接近灰阶。 */
  private static final double NEUTRAL_CHROMA = 6.0;

  private static final int TONE_ACTIVE = 80;
  private static final int TONE_ON_ACTIVE = 20;
  private static final int TONE_TRACK = 30;
  private static final int TONE_OFF = 15;
  private static final int TONE_ON_OFF = 90;

  private static final int TONE_SECONDARY = 70;
  private static final int TONE_MUTED = 50;
  private static final int TONE_STATUS = 95;

  /** QuantizerCelebi 目标簇数量。 */
  private static final int QUANTIZE_MAX_COLORS = 64;
  /** alpha 低于该值的像素被忽略；其余像素强制为不透明。 */
  private static final int MIN_ALPHA = 128;

  /** 用于派生本调色板的种子色（ARGB）。 */
  public final int seed;
  /** 强调色主色（accent / T80）。 */
  public final int active;
  /** 强调色上的前景色（accent / T20）。 */
  public final int onActive;
  /** 中性底色（neutral / T15）。 */
  public final int off;
  /** 中性底色上的前景色（neutral / T90）。 */
  public final int onOff;
  /** 强调色轨道/次要底色（accent / T30）。 */
  public final int track;
  /** 次要中性色（neutral / T70），用于黑底上的次级文字。 */
  public final int secondary;
  /** 弱化中性色（neutral / T50），用于黑底上的更弱文字。 */
  public final int muted;
  /** 状态栏近白主题色（同色相 / C4 / T95）。 */
  public final int status;

  private ThemePalette(
      int seed,
      int active,
      int onActive,
      int off,
      int onOff,
      int track,
      int secondary,
      int muted,
      int status) {
    this.seed = seed;
    this.active = active;
    this.onActive = onActive;
    this.off = off;
    this.onOff = onOff;
    this.track = track;
    this.secondary = secondary;
    this.muted = muted;
    this.status = status;
  }

  /**
   * 由单一 ARGB 种子色派生调色板。使用种子色的 HCT 色相，分别生成强调色与中性色两套固定色阶。
   *
   * @param seed ARGB 种子色；alpha 不参与取色，仅保存在 {@link #seed} 字段中。
   * @return 由该种子色派生的调色板。
   */
  public static ThemePalette fromSeed(int seed) {
    double hue = Hct.fromInt(seed).getHue();
    int active = Hct.from(hue, ACCENT_CHROMA, TONE_ACTIVE).toInt();
    int onActive = Hct.from(hue, ACCENT_CHROMA, TONE_ON_ACTIVE).toInt();
    int track = Hct.from(hue, ACCENT_CHROMA, TONE_TRACK).toInt();
    int off = Hct.from(hue, NEUTRAL_CHROMA, TONE_OFF).toInt();
    int onOff = Hct.from(hue, NEUTRAL_CHROMA, TONE_ON_OFF).toInt();
    int secondary = Hct.from(hue, NEUTRAL_CHROMA, TONE_SECONDARY).toInt();
    int muted = Hct.from(hue, NEUTRAL_CHROMA, TONE_MUTED).toInt();
    int status = Hct.from(hue, 4.0, TONE_STATUS).toInt();
    return new ThemePalette(seed, active, onActive, off, onOff, track, secondary, muted, status);
  }

  /**
   * 由一组 ARGB 像素派生调色板。
   *
   * <p>alpha&lt;128 的像素被忽略，其余像素强制为不透明后量化取色；没有任何可用像素时返回
   * {@code fromSeed(fallbackSeed)}。
   *
   * @param pixels ARGB 像素，允许包含透明或半透明值；不会被本方法修改。
   * @param fallbackSeed 无可用像素或取色失败时使用的 ARGB 回退种子色。
   * @return 由像素主色（或回退色）派生的调色板。
   */
  public static ThemePalette fromPixels(int[] pixels, int fallbackSeed) {
    int[] opaque = filterOpaque(pixels);
    if (opaque.length == 0) {
      return fromSeed(fallbackSeed);
    }
    Map<Integer, Integer> quantized = QuantizerCelebi.quantize(opaque, QUANTIZE_MAX_COLORS);
    int seed = Score.score(quantized, 1, fallbackSeed, true).get(0);
    return fromSeed(seed);
  }

  /**
   * 过滤掉 alpha&lt;128 的像素并把其余像素强制为不透明，保持原始相对顺序，不修改入参数组。
   */
  private static int[] filterOpaque(int[] pixels) {
    if (pixels == null || pixels.length == 0) {
      return new int[0];
    }
    int[] buffer = new int[pixels.length];
    int count = 0;
    for (int pixel : pixels) {
      if (alphaOf(pixel) < MIN_ALPHA) {
        continue;
      }
      buffer[count++] = pixel | 0xFF000000;
    }
    if (count == buffer.length) {
      return buffer;
    }
    int[] opaque = new int[count];
    System.arraycopy(buffer, 0, opaque, 0, count);
    return opaque;
  }

  private static int alphaOf(int argb) {
    return (argb >>> 24) & 0xFF;
  }
}
