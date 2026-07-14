package com.bajins.ocr.utils.barcode;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;
import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

/**
 * OpenCV + ZXing 增强型条码识别器
 * 针对模糊、反光、密集、太小、倾斜标签优化
 * <p>
 * | 恶劣条件      | 对应策略                | OpenCV 操作                 | 原理                     |
 * | --------- | ------------------- | ------------------------- | ---------------------- |
 * | **模糊**    | `sharpen`           | 双边滤波 + Unsharp Mask + 均衡化 | 增强边缘梯度，补偿失焦            |
 * | **反光/阴影** | `standard` / `otsu` | CLAHE + 自适应阈值 / OTSU      | 局部对比度增强，抵抗不均匀光照        |
 * | **太小**    | `2x` / `3x`         | `resize` 2~3 倍 + 预处理      | 放大后条码模块尺寸进入 ZXing 敏感区间 |
 * | **倾斜**    | 全策略 × 4 角度          | `rotate` 0/90/180/270°    | 一维码对旋转敏感，二维码相对鲁棒       |
 * | **黑白反转**  | `inverted`          | `bitwise_not`             | 应对反光导致的反色标签            |
 * | **密集/粘连** | `standard`          | 中值滤波 + 开运算 (2×2)          | 温和去噪，避免腐蚀破坏条码          |
 * <p>
 * 透视校正（Perspective Correction）：用 OpenCV 检测标签外轮廓 -> 四点变换 -> 拉平后再识别（适合拍摄角度倾斜）
 * <p>
 * | 步骤           | 操作                                            | 目的                        |
 * | ------------ | --------------------------------------------- | ------------------------- |
 * | **边缘检测**     | `GaussianBlur` + `Canny` + `dilate`           | 连接标签断裂边缘，形成闭合轮廓           |
 * | **轮廓筛选**     | `findContours` + `approxPolyDP` + 面积过滤        | 找到标签外框（最大四边形，占图面积 5%~95%） |
 * | **四边形验证**    | 边长 ≥ 30px，长宽比 0.2~5                           | 排除噪点、细长条纹、图像边界            |
 * | **顶点排序**     | 按 Y 分上下两组，再按 X 分左右                            | 确保透视变换顶点一一对应              |
 * | **透视变换**     | `getPerspectiveTransform` + `warpPerspective` | 将倾斜标签拉平为正视矩形              |
 * | **Fallback** | 如果找不到四边形，返回 `null`，后续策略继续尝试原始图                | 兼容无倾斜或轮廓不清的标签             |
 * <p>
 * 设计要点：
 * <ul>
 *   <li>资源所有权：{@code scanInternal} 在 finally 中释放传入的 {@code original} 与内部 {@code gray}；
 *       各预处理策略产出的 Mat 由 {@code runStrategy} 释放，基图（gray/warped）由各自的拥有方释放。</li>
 *   <li>线程安全：ZXing {@code GenericMultipleBarcodeReader} 非线程安全，{@link #scanInternal} 以实例锁串行化；
 *       高并发场景请为每个线程创建独立实例，而非放开锁。</li>
 * </ul>
 */
public class OpenCVZxingQrReader implements AutoCloseable {

    /**
     * 连续无新增的策略数达到此阈值且已有结果时，跳过后续重策略
     */
    private static final int EMPTY_LIMIT = 2;
    /**
     * 候选轮廓占图像面积比下限
     */
    private static final double AREA_MIN_RATIO = 0.05;
    /**
     * 候选轮廓占图像面积比上限
     */
    private static final double AREA_MAX_RATIO = 0.95;
    /**
     * 四边形最小边长（px），小于此视为噪点
     */
    private static final double MIN_QUAD_EDGE = 30;
    /**
     * 标签四边形长宽比下限
     */
    private static final double ASPECT_MIN = 0.2;
    /**
     * 标签四边形长宽比上限
     */
    private static final double ASPECT_MAX = 5.0;
    /**
     * 透视矫正后图的最小宽高（px），过小视为畸形丢弃
     */
    private static final int MIN_WARP_SIZE = 100;
    /**
     * 去重时中心点距离的最小阈值（px），避免包围盒过小时误判
     */
    private static final double DUP_MIN_DIST = 20;
    /**
     * 去重距离阈值占旧结果包围盒对角线的比例
     */
    private static final double DUP_DIST_RATIO = 0.5;
    /**
     * 触发 2 倍放大预处理的最小图像短边（px）
     */
    private static final int UPSCALE_2X_MAX = 400;
    /**
     * 触发 3 倍放大预处理的最小图像短边（px）
     */
    private static final int UPSCALE_3X_MAX = 200;

    static {
        // 自动加载对应平台的 OpenCV 原生库（无需系统安装）
        OpenCV.loadLocally();
    }

    /**
     * 轻量策略 1-5：原始灰度 -> 标准预处理 -> OTSU -> 高锐化 -> 颜色反转（均无条件启用）
     */
    private static final List<Strategy> LIGHT_STRATEGIES = List.of(
            new Strategy("original", base -> null),
            new Strategy("standard", OpenCVZxingQrReader::standardPreprocess),
            new Strategy("otsu", OpenCVZxingQrReader::otsuPreprocess),
            new Strategy("sharpen", OpenCVZxingQrReader::sharpenPreprocess),
            new Strategy("inverted", OpenCVZxingQrReader::invertPreprocess)
    );

    private final MultipleBarcodeReader multiReader;
    private final Map<DecodeHintType, Object> hints;

    /**
     * 构造识别器：初始化 ZXing 多码读取器与解码提示（限定码制 + TRY_HARDER）。
     */
    public OpenCVZxingQrReader() {
        MultiFormatReader formatReader = new MultiFormatReader();
        multiReader = new GenericMultipleBarcodeReader(formatReader);

        hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // 限定支持的码制，避免 MultiFormatReader 盲试全部 ~15 种格式拖慢速度
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.CODABAR,
                BarcodeFormat.ITF
        ));
    }

    /**
     * 识别图片文件中的所有条码（支持 1D/2D 混合、多码、各种恶劣条件）。
     *
     * @param imageFile 待识别的图片文件
     * @return 去重后的全部识别结果（可能为空，但不为 null）
     * @throws IOException 图片不存在或无法解码时抛出
     */
    public List<BarcodeResult> scan(File imageFile) throws IOException {
        Mat src = Imgcodecs.imread(imageFile.getAbsolutePath());
        if (src.empty()) {
            src.release();
            throw new IOException("无法读取图片: " + imageFile.getAbsolutePath());
        }
        // scanInternal 在 finally 中释放 src，调用方无需再释放
        return scanInternal(src);
    }

    /**
     * 直接识别 OpenCV Mat。内部克隆保护原图，调用方后续需自行释放传入的 Mat。
     *
     * @param src 待识别的 Mat（不会被修改，由调用方负责释放）
     * @return 去重后的全部识别结果（可能为空，但不为 null）
     */
    public List<BarcodeResult> scan(Mat src) {
        // 克隆一份：scanInternal 会释放传入的 Mat，故隔离以保护调用方的原图
        return scanInternal(src.clone());
    }

    @Override
    public void close() {
        // ZXing 的 reader 无显式 close，依赖 GC 即可
    }

    /**
     * 核心识别管道：在灰度图上依次执行多套预处理策略，每策略做 4 角度旋转补偿解码，
     * 命中后早停以避免不必要的重策略开销，所有策略结果去重后汇总返回。
     * <p>实例锁串行化，保护非线程安全的 {@code multiReader}。
     *
     * @param original 源图像 Mat（由本方法在 finally 中释放，调用方无需再释放）
     * @return 去重后的全部识别结果（可能为空，但不为 null）
     */
    private synchronized List<BarcodeResult> scanInternal(Mat original) {
        List<BarcodeResult> allResults = new ArrayList<>();

        // 统一转灰度作为所有策略的输入
        Mat gray = new Mat();
        try {
            if (original.channels() == 3 || original.channels() == 4) {
                Imgproc.cvtColor(original, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                original.copyTo(gray);
            }

            /*
             * 策略按"轻 -> 重"排序：典型标签在前几个轻量策略即可命中，命中后早停避免重策略。
             * 早停：已识别到码 且 连续 EMPTY_LIMIT 个策略无新增 -> 跳过后续重策略（透视矫正/放大）。
             * 若一个码都没识别到（allResults 为空），即使连续无新增也继续尝试救命策略，
             * 避免倾斜/极小标签因前几个增强策略未命中而被漏识。
             */
            int consecutiveEmpty = 0;
            int minDim = Math.min(gray.width(), gray.height());

            // 策略 1-5：轻量预处理序列
            for (Strategy s : LIGHT_STRATEGIES) {
                consecutiveEmpty = tryStrategy(gray, consecutiveEmpty,
                        s.name(), s.enabled(), s.preprocess(), allResults);
            }

            // 策略 6：透视矫正 + 标准预处理 / 原始（重，置后；解决拍摄倾斜）
            // 外层守卫避免在已触发早停时仍执行昂贵的轮廓检测与透视变换
            if (consecutiveEmpty < EMPTY_LIMIT || allResults.isEmpty()) {
                Mat warped = perspectiveCorrect(gray);
                if (warped != null) {
                    if (!warped.empty()) {
                        consecutiveEmpty = tryStrategy(warped, consecutiveEmpty, "perspective+standard",
                                true, OpenCVZxingQrReader::standardPreprocess, allResults);
                        consecutiveEmpty = tryStrategy(warped, consecutiveEmpty, "perspective",
                                true, base -> null, allResults);
                    }
                    warped.release();
                }
            }

            // 策略 7-8：放大 2x/3x（图小触发；放大后条码模块尺寸进入 ZXing 敏感区间）
            for (Strategy s : upscaleStrategies(minDim)) {
                consecutiveEmpty = tryStrategy(gray, consecutiveEmpty,
                        s.name(), s.enabled(), s.preprocess(), allResults);
            }
        } finally {
            gray.release();
            original.release();
        }
        return allResults;
    }

    /**
     * 构建放大策略 7-8：根据图像短边决定是否启用 2x/3x。
     *
     * @param minDim 灰度图短边像素数
     * @return 放大策略列表（含是否启用的附加条件）
     */
    private static List<Strategy> upscaleStrategies(int minDim) {
        return List.of(
                new Strategy("2x", base -> upscalePreprocess(base, 2), minDim < UPSCALE_2X_MAX),
                new Strategy("3x", base -> upscalePreprocess(base, 3), minDim < UPSCALE_3X_MAX)
        );
    }

    /**
     * 条件执行单个识别策略并更新连续空结果计数。
     * <p>早停规则：连续 {@value #EMPTY_LIMIT} 个策略无新增 且 已有结果时跳过（避免重策略开销）；
     * 但若尚无任何结果，即便连续无新增也继续尝试救命策略。附加条件 {@code enabled} 为 false 时也跳过，
     * 且不累加计数（保持原语义：尺寸门槛不满足时既不执行也不影响早停判定）。
     *
     * @param base             解码基图（preprocess 的输入；processed 为 null 时直接解码此图），由调用方释放
     * @param consecutiveEmpty 当前连续无新增的策略数
     * @param name             策略名（用于结果溯源）
     * @param enabled          附加启用条件（如图像尺寸门槛）；false 时整体跳过
     * @param preprocess       预处理函数，返回待解码 Mat（由本方法经 runStrategy 释放）；返回 null 表示直接解码 base
     * @param results          累积结果列表（原地新增）
     * @return 更新后的连续空结果计数
     */
    private int tryStrategy(Mat base, int consecutiveEmpty, String name, boolean enabled,
                            Function<Mat, Mat> preprocess, List<BarcodeResult> results) {
        // 早停或附加条件不满足 -> 跳过，且不累加连续空计数
        if (!enabled || (consecutiveEmpty >= EMPTY_LIMIT && !results.isEmpty())) {
            return consecutiveEmpty;
        }
        Mat processed = preprocess.apply(base);
        int added = runStrategy(base, name, processed, results);
        return added > 0 ? 0 : consecutiveEmpty + 1;
    }

    /**
     * 执行单个识别策略：在预处理后的 Mat 上解码，新增结果到 results。
     *
     * @param ownedInput 当 processed 为 null 时的解码输入（由主流程统一释放，本方法不释放）
     * @param strategy   策略名（用于结果溯源）
     * @param processed  预处理产物 Mat；非 null 时由本方法在 finally 中释放，为 null 则使用 ownedInput
     * @param results    累积结果列表（原地新增）
     * @return 本次策略新增的结果数量
     */
    private int runStrategy(Mat ownedInput, String strategy, Mat processed, List<BarcodeResult> results) {
        int before = results.size();
        Mat input = processed != null ? processed : ownedInput;
        try {
            tryDecode(input, strategy, results);
        } finally {
            // processed 由本策略创建，无论解码成功或抛异常都必须释放；ownedInput 由主流程释放
            if (processed != null) {
                processed.release();
            }
        }
        return results.size() - before;
    }

    /**
     * 透视矫正：检测标签外轮廓（最大四边形）并拉平为正视图。
     *
     * @param gray 输入灰度图
     * @return 矫正后的 Mat；如果未找到有效四边形则返回 null
     */
    private static Mat perspectiveCorrect(Mat gray) {
        // 边缘检测预处理：降噪 -> Canny -> 膨胀连接断裂边缘，形成闭合轮廓
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat dilated = new Mat();
        Mat hierarchy = new Mat();
        Mat kernel = null;
        List<MatOfPoint> contours = new ArrayList<>();
        MatOfPoint2f bestQuad;
        try {
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.Canny(blurred, edges, 50, 150);
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Imgproc.dilate(edges, dilated, kernel);
            Imgproc.findContours(dilated, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            // 筛选面积最大的有效四边形（通常是标签外框）
            bestQuad = findLargestQuadrilateral(contours, gray.size());
        } finally {
            // 中间产物无论成功或异常都释放，避免 native 内存泄漏
            blurred.release();
            edges.release();
            dilated.release();
            hierarchy.release();
            if (kernel != null) {
                kernel.release();
            }
            for (MatOfPoint c : contours) {
                c.release();
            }
        }

        if (bestQuad == null) {
            return null;
        }

        // 顶点排序：左上、右上、右下、左下，确保透视变换顶点一一对应
        Point[] sorted = sortCorners(bestQuad.toArray());
        bestQuad.release();

        // 目标尺寸取四边形两组对边的最大值
        double w1 = distance(sorted[0], sorted[1]);
        double w2 = distance(sorted[2], sorted[3]);
        double h1 = distance(sorted[0], sorted[3]);
        double h2 = distance(sorted[1], sorted[2]);
        int maxWidth = (int) Math.max(w1, w2);
        int maxHeight = (int) Math.max(h1, h2);
        // 过小或畸形直接放弃，避免无效变换
        if (maxWidth < MIN_WARP_SIZE || maxHeight < MIN_WARP_SIZE) {
            return null;
        }

        // 透视变换：将倾斜四边形拉平为正视矩形
        MatOfPoint2f srcPoints = new MatOfPoint2f(sorted);
        MatOfPoint2f dstPoints = new MatOfPoint2f(
                new Point(0, 0),
                new Point(maxWidth - 1, 0),
                new Point(maxWidth - 1, maxHeight - 1),
                new Point(0, maxHeight - 1));
        Mat transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
        Mat warped = new Mat();
        Imgproc.warpPerspective(gray, warped, transform, new Size(maxWidth, maxHeight));

        srcPoints.release();
        dstPoints.release();
        transform.release();
        return warped;
    }

    /**
     * 从轮廓列表中找到面积最大的有效四边形（通常是标签外框）。
     *
     * @param contours  候选轮廓列表（调用方负责释放其中的 MatOfPoint）
     * @param imageSize 原图尺寸，用于按面积比例过滤
     * @return 最大有效四边形的 MatOfPoint2f；无符合条件者返回 null
     */
    private static MatOfPoint2f findLargestQuadrilateral(List<MatOfPoint> contours, Size imageSize) {
        MatOfPoint2f bestQuad = null;
        double maxArea = 0;
        double imageArea = imageSize.width * imageSize.height;

        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            // 过滤太小或太大的轮廓（占图面积 5%~95%）
            if (area < imageArea * AREA_MIN_RATIO || area > imageArea * AREA_MAX_RATIO) {
                continue;
            }

            MatOfPoint2f approx = new MatOfPoint2f();
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            double peri = Imgproc.arcLength(contour2f, true);
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true);
            contour2f.release();

            // 必须是四边形，且通过有效性校验（边长/长宽比）
            if (approx.total() == 4 && area > maxArea && isValidQuadrilateral(approx.toArray())) {
                if (bestQuad != null) {
                    bestQuad.release();
                }
                bestQuad = (MatOfPoint2f) approx.clone();
                maxArea = area;
            }
            approx.release();
        }
        return bestQuad;
    }

    /**
     * 验证四边形是否有效：边长足够、非退化细长形状。
     *
     * @param pts 四个顶点（按轮廓顺序）
     * @return 有效返回 true，否则 false
     */
    private static boolean isValidQuadrilateral(Point[] pts) {
        if (pts.length != 4) {
            return false;
        }
        // 四条边长度（按轮廓顺序：0-1, 1-2, 2-3, 3-0）
        double[] lengths = new double[4];
        for (int i = 0; i < 4; i++) {
            lengths[i] = distance(pts[i], pts[(i + 1) % 4]);
            if (lengths[i] < MIN_QUAD_EDGE) {
                return false; // 边过短，疑似噪点
            }
        }
        // 退化检查：取一组对边的最大值与另一组对边的最小值之比，过滤极细长形状
        double pairAMax = Math.max(lengths[0], lengths[2]);
        double pairBMin = Math.min(lengths[1], lengths[3]);
        double ratio = pairAMax / Math.max(pairBMin, 1);
        return ratio > ASPECT_MIN && ratio < ASPECT_MAX;
    }

    /**
     * 对四边形顶点排序：左上、右上、右下、左下。
     *
     * @param pts 四个无序顶点
     * @return 排序后的顶点数组（TL, TR, BR, BL）
     */
    private static Point[] sortCorners(Point[] pts) {
        Point[] sorted = pts.clone();
        // 按 y 坐标排序，前两个是上方，后两个是下方
        Arrays.sort(sorted, Comparator.comparingDouble(p -> p.y));
        Point[] top = Arrays.copyOfRange(sorted, 0, 2);
        Point[] bottom = Arrays.copyOfRange(sorted, 2, 4);

        // 上方两点按 x 排序：左、右
        Arrays.sort(top, Comparator.comparingDouble(p -> p.x));
        // 下方两点按 x 排序：左、右
        Arrays.sort(bottom, Comparator.comparingDouble(p -> p.x));

        return new Point[]{top[0], top[1], bottom[1], bottom[0]};
    }

    /**
     * 计算两点欧氏距离。
     *
     * @param p1 起点
     * @param p2 终点
     * @return 距离
     */
    private static double distance(Point p1, Point p2) {
        return Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    /**
     * 对给定图像尝试 0°/90°/180°/270° 识别，新增结果到 results（含跨角度去重）。
     *
     * @param mat      待解码的 Mat（灰度或预处理产物）
     * @param strategy 策略名（用于结果溯源）
     * @param results  累积结果列表（原地新增）
     */
    private void tryDecode(Mat mat, String strategy, List<BarcodeResult> results) {
        BufferedImage image = matToBufferedImage(mat);
        if (image == null) {
            return;
        }
        // 一维码对旋转敏感，尝试 0°、90°、180°、270°
        for (int angle = 0; angle < 360; angle += 90) {
            BufferedImage rotated = rotateImage(image, angle);
            try {
                Result[] found = decodeBarcodes(rotated);
                for (Result r : found) {
                    // 将旋转后图像的坐标统一反旋转回原始图坐标系
                    ResultPoint[] originalPoints = unrotatePoints(
                            r.getResultPoints(), rotated.getWidth(), rotated.getHeight(), angle);
                    if (!isDuplicate(r.getText(), r.getBarcodeFormat().toString(),
                            originalPoints, results)) {
                        results.add(new BarcodeResult(
                                r.getText(),
                                r.getBarcodeFormat().toString(),
                                originalPoints,
                                strategy,
                                angle
                        ));
                    }
                }
            } catch (NotFoundException ignored) {
                // 该策略/角度下未识别，属于正常情况
            }
        }
    }

    /**
     * 判断新识别结果是否与已有结果重复（同一个物理条码）。
     * 条件：码制相同 + 内容相同 + 位置重叠 -> 才视为重复。
     * 若内容相同但位置不同，说明是图中不同位置的不同码，不去重。
     * <p>所有坐标已统一为原始图坐标系，可直接比较。
     *
     * @param text     新结果的条码内容
     * @param type     新结果的码制
     * @param points   新结果的角点（原始图坐标系）
     * @param existing 已有结果列表
     * @return 重复返回 true，否则 false
     */
    private static boolean isDuplicate(String text, String type, ResultPoint[] points, List<BarcodeResult> existing) {
        for (BarcodeResult old : existing) {
            // 码制和内容都必须相同才有可能是重复
            if (!old.type().equals(type) || !old.data().equals(text)) {
                continue;
            }
            // 计算两个结果在原始图坐标系下的中心点距离
            double dist = centerDistance(points, old.points());
            // 阈值取旧结果包围盒对角线的 50%，最小 20px，若距离小于阈值则判定为同一码
            double threshold = boundingBoxDiagonal(old.points()) * DUP_DIST_RATIO;
            if (dist < Math.max(threshold, DUP_MIN_DIST)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算两组点在原始图坐标系下的中心点距离。
     *
     * @param points1 第一组点
     * @param points2 第二组点
     * @return 中心点欧氏距离
     */
    private static double centerDistance(ResultPoint[] points1, ResultPoint[] points2) {
        double cx1 = 0, cy1 = 0;
        for (ResultPoint p : points1) {
            cx1 += p.getX();
            cy1 += p.getY();
        }
        cx1 /= points1.length;
        cy1 /= points1.length;

        double cx2 = 0, cy2 = 0;
        for (ResultPoint p : points2) {
            cx2 += p.getX();
            cy2 += p.getY();
        }
        cx2 /= points2.length;
        cy2 /= points2.length;

        return Math.sqrt(Math.pow(cx1 - cx2, 2) + Math.pow(cy1 - cy2, 2));
    }

    /**
     * 将旋转后图像上的坐标反旋转回原始图坐标系。
     * 旋转逻辑与 {@link #rotateImage} 对应：
     * <ul>
     *   <li>0°：不变</li>
     *   <li>90°：原图 (x,y) -> 旋转图 (rotW-y, x)，故反旋转 (rx,ry) -> (ry, rotW-rx)</li>
     *   <li>180°：原图 (x,y) -> 旋转图 (rotW-x, rotH-y)，故反旋转 (rx,ry) -> (rotW-rx, rotH-ry)</li>
     *   <li>270°：原图 (x,y) -> 旋转图 (y, rotH-x)，故反旋转 (rx,ry) -> (rotH-ry, rx)</li>
     * </ul>
     *
     * @param points 旋转后图像坐标系下的点
     * @param rotW   旋转后图像宽度
     * @param rotH   旋转后图像高度
     * @param angle  旋转角度
     * @return 原始图坐标系下的点数组
     */
    private static ResultPoint[] unrotatePoints(ResultPoint[] points, int rotW, int rotH, int angle) {
        if (angle == 0) {
            return points;
        }

        ResultPoint[] result = new ResultPoint[points.length];
        for (int i = 0; i < points.length; i++) {
            double x = points[i].getX();
            double y = points[i].getY();
            double ox, oy;
            switch (angle) {
                case 90 -> {
                    ox = y;
                    oy = rotW - x;
                }
                case 180 -> {
                    ox = rotW - x;
                    oy = rotH - y;
                }
                case 270 -> {
                    ox = rotH - y;
                    oy = x;
                }
                default -> {
                    ox = x;
                    oy = y;
                }
            }
            result[i] = new ResultPoint((float) ox, (float) oy);
        }
        return result;
    }

    /**
     * 计算 ResultPoint 包围盒的对角线长度。
     *
     * @param points 点数组
     * @return 包围盒对角线长度
     */
    private static double boundingBoxDiagonal(ResultPoint[] points) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
        for (ResultPoint p : points) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
        }
        return Math.sqrt(Math.pow(maxX - minX, 2) + Math.pow(maxY - minY, 2));
    }

    /**
     * 调用 ZXing 多码读取器一次性解码图中所有条码。
     *
     * @param image 待解码图像
     * @return 识别到的所有结果数组
     * @throws NotFoundException 图中未识别到任何条码时抛出（由调用方视为正常情况捕获）
     */
    private Result[] decodeBarcodes(BufferedImage image) throws NotFoundException {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        return multiReader.decodeMultiple(bitmap, hints);
    }

    /**
     * 标准预处理：去噪 -> CLAHE 对比度增强 -> 自适应阈值 -> 开运算去噪点 -> 锐化。
     *
     * @param gray 输入灰度图
     * @return 预处理产物 Mat（调用方负责释放）
     */
    private static Mat standardPreprocess(Mat gray) {
        // 中值滤波（保边去噪，适合密集标签）
        Mat denoised = new Mat();
        Imgproc.medianBlur(gray, denoised, 5);

        // CLAHE 自适应直方图均衡化（解决局部反光/阴影）
        Mat enhanced = new Mat();
        CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        clahe.apply(denoised, enhanced);

        // 自适应高斯阈值（适合光照不均）
        Mat binary = new Mat();
        Imgproc.adaptiveThreshold(enhanced, binary, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 11, 2);

        // 形态学开运算（去除孤立噪点，分离粘连条码）
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
        Mat opened = new Mat();
        Imgproc.morphologyEx(binary, opened, Imgproc.MORPH_OPEN, kernel);

        // Unsharp Mask 锐化
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(opened, blurred, new Size(0, 0), 3);
        Mat sharpened = new Mat();
        Core.addWeighted(opened, 1.5, blurred, -0.5, 0, sharpened);

        // 释放中间资源
        denoised.release();
        enhanced.release();
        binary.release();
        kernel.release();
        opened.release();
        blurred.release();

        return sharpened;
    }

    /**
     * OTSU 预处理：高斯模糊 -> OTSU 自动阈值。适用整体光照不均、强反光导致对比度骤降。
     *
     * @param gray 输入灰度图
     * @return 预处理产物 Mat（调用方负责释放）
     */
    private static Mat otsuPreprocess(Mat gray) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

        Mat binary = new Mat();
        Imgproc.threshold(blurred, binary, 0, 255,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

        blurred.release();
        return binary;
    }

    /**
     * 锐化预处理：双边滤波（保边）-> 锐化 -> 直方图均衡化。适用打印模糊、运动模糊、低分辨率打印。
     *
     * @param gray 输入灰度图
     * @return 预处理产物 Mat（调用方负责释放）
     */
    private static Mat sharpenPreprocess(Mat gray) {
        // 双边滤波（去噪同时保持边缘锐利）
        Mat filtered = new Mat();
        Imgproc.bilateralFilter(gray, filtered, 9, 75, 75);

        // Unsharp Mask
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(filtered, blurred, new Size(0, 0), 3);
        Mat sharpened = new Mat();
        Core.addWeighted(filtered, 1.5, blurred, -0.5, 0, sharpened);

        // 全局均衡化提升整体对比度
        Mat equalized = new Mat();
        Imgproc.equalizeHist(sharpened, equalized);

        filtered.release();
        blurred.release();
        sharpened.release();
        return equalized;
    }

    /**
     * 颜色反转预处理：应对反光导致白底黑条变黑底白条。
     *
     * @param gray 输入灰度图
     * @return 反转后的 Mat（调用方负责释放）
     */
    private static Mat invertPreprocess(Mat gray) {
        Mat inverted = new Mat();
        Core.bitwise_not(gray, inverted);
        return inverted;
    }

    /**
     * 放大预处理：放大指定倍数后接标准预处理。放大后条码模块尺寸进入 ZXing 敏感区间。
     *
     * @param gray   输入灰度图
     * @param factor 放大倍数
     * @return 预处理产物 Mat（调用方负责释放）
     */
    private static Mat upscalePreprocess(Mat gray, int factor) {
        Mat big = new Mat();
        Imgproc.resize(gray, big, new Size(gray.width() * factor, gray.height() * factor),
                0, 0, Imgproc.INTER_CUBIC);
        Mat processed = standardPreprocess(big);
        big.release();
        return processed;
    }

    /**
     * 将 OpenCV Mat 转为 BufferedImage。4 通道（BGRA）先转 3 通道 BGR，避免缓冲区与 raster 尺寸错配。
     *
     * @param mat 输入 Mat（灰度或 BGR/BGRA）
     * @return 对应的 BufferedImage
     */
    private static BufferedImage matToBufferedImage(Mat mat) {
        int channels = mat.channels();
        Mat src = mat;
        Mat converted = null;
        // BGRA 需先剥离 alpha 通道，否则数据长度与 3BYTE_BGR raster 不匹配
        if (channels == 4) {
            converted = new Mat();
            Imgproc.cvtColor(mat, converted, Imgproc.COLOR_BGRA2BGR);
            src = converted;
            channels = 3;
        }
        int w = src.cols(), h = src.rows();
        byte[] data = new byte[w * h * channels];
        src.get(0, 0, data);
        int imageType = channels == 1 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage img = new BufferedImage(w, h, imageType);
        img.getRaster().setDataElements(0, 0, w, h, data);
        if (converted != null) {
            converted.release();
        }
        return img;
    }

    /**
     * 旋转 BufferedImage（0°/90°/180°/270°）。0° 时直接返回原图，不创建新对象。
     *
     * @param src   源图像
     * @param angle 旋转角度（必须是 0/90/180/270 之一）
     * @return 旋转后的图像
     */
    private static BufferedImage rotateImage(BufferedImage src, int angle) {
        if (angle == 0) {
            return src;
        }

        int w = src.getWidth(), h = src.getHeight();
        AffineTransform tx = new AffineTransform();

        switch (angle) {
            case 90 -> {
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
            }
            case 180 -> {
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 270 -> {
                tx.translate(0, w);
                tx.rotate(-Math.PI / 2);
            }
            default -> { /* 仅支持 90° 倍数，其余不处理 */ }
        }

        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_BILINEAR);
        return op.filter(src, null);
    }

    /**
     * 单个识别策略描述：名称 + 预处理函数 + 附加启用条件。
     *
     * @param name       策略名（用于结果溯源）
     * @param preprocess 预处理函数，返回待解码 Mat；返回 null 表示直接解码基图
     * @param enabled    附加启用条件（如图像尺寸门槛）；false 时整体跳过
     */
    private record Strategy(String name, Function<Mat, Mat> preprocess, boolean enabled) {
        /**
         * 无附加条件的策略构造（始终启用）
         */
        Strategy(String name, Function<Mat, Mat> preprocess) {
            this(name, preprocess, true);
        }
    }

    /**
     * 单个条码识别结果。
     *
     * @param data     条码内容文本
     * @param type     码制（如 QR_CODE、CODE_128）
     * @param points   条码角点坐标（已统一回原始图坐标系）
     * @param strategy 命中时所用的预处理策略名
     * @param angle    命中时的旋转角度（0/90/180/270）
     */
    public record BarcodeResult(String data, String type, ResultPoint[] points, String strategy, int angle) {
        @Override
        public String toString() {
            return String.format("[%s] %s (strategy=%s, angle=%d°)", type, data, strategy, angle);
        }
    }

    public static void main(String[] args) {
        // 从 classpath 加载示例图片
        URL url = Thread.currentThread().getContextClassLoader().getResource("images/2026-05-05_163050.png");
        if (url == null) {
            System.err.println("未找到资源 images/2026-05-05_163050.png");
            return;
        }
        String path;
        try {
            // toURI 自动处理编码与特殊字符，再转回本地路径字符串
            path = Paths.get(url.toURI()).toString();
        } catch (URISyntaxException e) {
            System.err.println("资源 URI 解析失败: " + e.getMessage());
            return;
        }
        try (OpenCVZxingQrReader scanner = new OpenCVZxingQrReader()) {
            List<BarcodeResult> results = scanner.scan(new File(path));
            System.out.println("共识别到 " + results.size() + " 个唯一条码：");
            results.forEach(System.out::println);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
