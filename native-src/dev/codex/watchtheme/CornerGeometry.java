package dev.codex.watchtheme;

/**
 * 控制中心卡片的圆角几何，纯 Java 数学，无 Android 依赖，可在纯 JVM 中离线测试。
 *
 * <p>{@link #point} 给出 C2 连续角五阶 Bezier：从 {@code (0,R)} 到 {@code (R,0)}，控制点为
 * {@code (0,R), (0,R-d), (0,R-2d), (R-2d,0), (R-d,0), (R,0)}，其中
 * {@code d = (0.5-(1-1/sqrt(2)))*32/25*R}。曲线以 {@code (R,R)} 为名义圆心、半径 R，整条曲线
 * 偏离该名义圆的幅度小于 1%R；两端控制点共线，故端点曲率为 0，与直角边相接处 C2 连续。这里的
 * R 是切线延伸尺度，不代表任何特定厂商的精确路径。
 *
 * <p>{@link #radii} 为一次绘制给出 TL/TR/BR/BL 四个圆角半径：当某个角的两侧边距都等于
 * {@code inset}（容差 1px）时，该角贴近同一外角，取 {@code outerRadius - inset}；否则取
 * {@code minimum}。所有结果再夹紧到 {@code [0, min(width,height)/2]}。
 */
public final class CornerGeometry {

  /** 曲率衔接系数 d/R。 */
  private static final double D_FACTOR = (0.5 - (1.0 - 1.0 / Math.sqrt(2.0))) * 32.0 / 25.0;

  /** 边距匹配容差（px）：某角两侧边距都落在 inset±该值内即视为贴近同一外角。 */
  private static final double MARGIN_TOLERANCE = 1.0;

  private CornerGeometry() {}

  /** TL/TR/BR/BL horizontal radii followed by vertical radii. Unequal margins use an ellipse. */
  public static float[] axes(float screenWidth,float screenHeight,float x,float y,
      float width,float height,float outerRadius,float minimum) {
    float[] result=new float[8];
    float[] horizontal={x,screenWidth-x-width,screenWidth-x-width,x};
    float[] vertical={y,y,screenHeight-y-height,screenHeight-y-height};
    for(int i=0;i<4;i++){
      boolean adjacent=horizontal[i]>=0&&vertical[i]>=0&&horizontal[i]<outerRadius&&vertical[i]<outerRadius;
      result[i]=clamp(adjacent?outerRadius-horizontal[i]:minimum,Math.max(0,width/2));
      result[i+4]=clamp(adjacent?outerRadius-vertical[i]:minimum,Math.max(0,height/2));
    }
    return result;
  }

  /**
   * C2 连续角的五阶 Bezier 采样。坐标以该角的外角顶点为原点，x 向右、y 向下。
   *
   * @param radius 切线延伸尺度 R；{@code <=0} 或 NaN 时返回 {@code {0,0}}。
   * @param t 归一化参数，超出 {@code [0,1]} 时截断到端点。
   * @return {@code {x,y}}；t=0 处为 {@code (0,R)}，t=1 处为 {@code (R,0)}。
   */
  public static double[] point(double radius, double t) {
    if (!(radius > 0.0)) {
      return new double[] {0.0, 0.0};
    }
    double s = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    double d = D_FACTOR * radius;
    // 控制点中只有这三个坐标进入非零权重项，其余分量随 (1-t) 幂次归零。
    double p2y = radius - 2.0 * d;
    double p3x = radius - 2.0 * d;
    double p4 = radius - d;

    double u = 1.0 - s;
    double u2 = u * u;
    double s2 = s * s;
    double b0 = u2 * u2 * u;        // (1-t)^5
    double b1 = 5.0 * s * u2 * u2;  // 5t(1-t)^4
    double b2 = 10.0 * s2 * u2 * u; // 10t^2(1-t)^3
    double b3 = 10.0 * s2 * s * u2; // 10t^3(1-t)^2
    double b4 = 5.0 * s2 * s2 * u;  // 5t^4(1-t)
    double b5 = s2 * s2 * s;        // t^5

    double x = b3 * p3x + b4 * p4 + b5 * radius;
    double y = b0 * radius + b1 * p4 + b2 * p2y;
    return new double[] {x, y};
  }

  /**
   * 计算一次绘制所需的四个圆角半径，顺序为 TL/TR/BR/BL。
   *
   * <p>某个角贴近屏幕同一外角，当且仅当该角两条边的边距都落在 {@code inset ± 1px} 内：命中时取
   * {@code outerRadius - inset}，否则取 {@code minimum}。每个结果再夹紧到
   * {@code [0, min(width,height)/2]}，负数夹到 0。
   *
   * @param screenWidth 屏幕宽（px）
   * @param screenHeight 屏幕高（px）
   * @param x 卡片左边界
   * @param y 卡片上边界
   * @param width 卡片宽
   * @param height 卡片高
   * @param outerRadius 屏幕外角半径
   * @param inset 内容距屏幕边缘的标准内缩量，也是命中角的半径缩减量
   * @param minimum 未贴近外角时的兜底圆角
   * @return {@code float[4]} 顺序 TL/TR/BR/BL
   */
  public static float[] radii(float screenWidth, float screenHeight, float x, float y,
      float width, float height, float outerRadius, float inset, float minimum) {
    double left = x;
    double top = y;
    double right = screenWidth - (x + width);
    double bottom = screenHeight - (y + height);
    double hug = outerRadius - inset;
    double half = Math.max(0.0, Math.min(width, height) / 2.0);
    return new float[] {
      clamp(corner(left, top, inset, hug, minimum), half),
      clamp(corner(right, top, inset, hug, minimum), half),
      clamp(corner(right, bottom, inset, hug, minimum), half),
      clamp(corner(left, bottom, inset, hug, minimum), half)
    };
  }

  /** 两条边距都匹配 inset 时返回贴近外角的半径，否则返回 minimum。 */
  private static double corner(double sideA, double sideB, double inset, double hug, double minimum) {
    boolean hugs = Math.abs(sideA - inset) <= MARGIN_TOLERANCE
        && Math.abs(sideB - inset) <= MARGIN_TOLERANCE;
    return hugs ? hug : minimum;
  }

  /** 夹紧到 [0, half]（half 已为非负）。 */
  private static float clamp(double value, double half) {
    return (float) Math.min(Math.max(value, 0.0), half);
  }
}
