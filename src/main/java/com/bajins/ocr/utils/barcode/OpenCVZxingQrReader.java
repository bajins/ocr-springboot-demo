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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;

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
 * 透视校正（Perspective Correction）：用 OpenCV 检测标签外轮廓 → 四点变换 → 拉平后再识别（适合拍摄角度倾斜）
 * <p>
 * | 步骤           | 操作                                            | 目的                        |
 * | ------------ | --------------------------------------------- | ------------------------- |
 * | **边缘检测**     | `GaussianBlur` + `Canny` + `dilate`           | 连接标签断裂边缘，形成闭合轮廓           |
 * | **轮廓筛选**     | `findContours` + `approxPolyDP` + 面积过滤        | 找到标签外框（最大四边形，占图面积 5%~95%） |
 * | **四边形验证**    | 边长 ≥ 30px，长宽比 0.2~5                           | 排除噪点、细长条纹、图像边界            |
 * | **顶点排序**     | 按 Y 分上下两组，再按 X 分左右                            | 确保透视变换顶点一一对应              |
 * | **透视变换**     | `getPerspectiveTransform` + `warpPerspective` | 将倾斜标签拉平为正视矩形              |
 * | **Fallback** | 如果找不到四边形，返回 `null`，后续策略继续尝试原始图                | 兼容无倾斜或轮廓不清的标签             |
 */
public class OpenCVZxingQrReader implements AutoCloseable {

    static {
        // 自动加载对应平台的 OpenCV 原生库（无需系统安装）
        OpenCV.loadLocally();
    }

    private final MultipleBarcodeReader multiReader;
    private final Map<DecodeHintType, Object> hints;

    public OpenCVZxingQrReader() {
        MultiFormatReader formatReader = new MultiFormatReader();
        multiReader = new GenericMultipleBarcodeReader(formatReader);

        hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
//                BarcodeFormat.UPC_A,
//                BarcodeFormat.UPC_E,
                BarcodeFormat.CODABAR,
                BarcodeFormat.ITF
        ));
    }

    /* ===================== 公开 API ===================== */

    /**
     * 识别图片文件（支持 1D/2D 混合、多码、各种恶劣条件）
     */
    public List<BarcodeResult> scan(File imageFile) throws IOException {
        Mat src = Imgcodecs.imread(imageFile.getAbsolutePath());
        if (src.empty()) {
            throw new IOException("无法读取图片: " + imageFile.getAbsolutePath());
        }
        List<BarcodeResult> results = scanInternal(src);
        src.release();
        return results;
    }

    /**
     * 直接识别 OpenCV Mat（调用方后续需自行释放传入的 Mat）
     */
    public List<BarcodeResult> scan(Mat src) {
        Mat clone = src.clone(); // 保护原图
        List<BarcodeResult> results = scanInternal(clone);
        clone.release();
        return results;
    }

    @Override
    public void close() {
        // ZXing 的 reader 无显式 close，依赖 GC 即可
    }

    /* ===================== 核心识别管道 ===================== */

    private List<BarcodeResult> scanInternal(Mat original) {
        List<BarcodeResult> allResults = new ArrayList<>();

        // 统一转灰度(所有策略的输入)
        Mat gray = new Mat();
        try {
            if (original.channels() == 3 || original.channels() == 4) {
                Imgproc.cvtColor(original, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                original.copyTo(gray);
            }

            /*
             * 策略按"轻→重"排序,典型标签在前几个轻量策略即可命中,命中后早停避免重策略。
             * 早停:已识别到码 且 连续 EMPTY_LIMIT 个策略无新增 → 跳过后续重策略(透视矫正/放大)。
             * 若一个码都没识别到(allResults 为空),即使连续无新增也继续尝试后续救命策略,
             * 避免倾斜/极小标签因前几个增强策略未命中而被漏识。
             */
            final int emptyLimit = 2;
            int consecutiveEmpty = 0;
            int added;

            // 策略 1:原始灰度(最轻,直接解码)
            added = runStrategy(gray, "original", null, allResults);
            consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;

            // 策略 2:标准预处理(去噪+CLAHE+自适应阈值+锐化;适用模糊/低对比度/轻微反光)
            if (consecutiveEmpty < emptyLimit || allResults.isEmpty()) {
                added = runStrategy(gray, "standard", standardPreprocess(gray), allResults);
                consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
            }

            // 策略 3:OTSU 全局阈值(适用光照不均/阴影/强反光)
            if (consecutiveEmpty < emptyLimit || allResults.isEmpty()) {
                added = runStrategy(gray, "otsu", otsuPreprocess(gray), allResults);
                consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
            }

            // 策略 4:高锐化(适用运动模糊/打印质量差/边缘虚化)
            if (consecutiveEmpty < emptyLimit || allResults.isEmpty()) {
                added = runStrategy(gray, "sharpen", sharpenPreprocess(gray), allResults);
                consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
            }

            // 策略 5:颜色反转(适用反光导致白底黑条变黑底白条)
            if (consecutiveEmpty < emptyLimit || allResults.isEmpty()) {
                Mat inverted = new Mat();
                Core.bitwise_not(gray, inverted);
                added = runStrategy(gray, "inverted", inverted, allResults);
                consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
            }

            // 策略 6:透视矫正 + 标准预处理 + 矫正后直接识别(重,置后;解决拍摄倾斜)
            if (consecutiveEmpty < emptyLimit || allResults.isEmpty()) {
                Mat warped = perspectiveCorrect(gray);
                if (warped != null && !warped.empty()) {
                    added = runStrategy(warped, "perspective+standard", standardPreprocess(warped), allResults);
                    consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
                    if (consecutiveEmpty < emptyLimit || allResults.isEmpty()) {
                        added = runStrategy(warped, "perspective", null, allResults);
                        consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
                    }
                    warped.release();
                }
            }

            // 策略 7:放大 2x + 标准预处理(图小触发;适用条码太小/分辨率不足)
            if ((consecutiveEmpty < emptyLimit || allResults.isEmpty()) && Math.min(gray.width(), gray.height()) < 400) {
                Mat big = new Mat();
                Imgproc.resize(gray, big, new Size(gray.width() * 2, gray.height() * 2),
                        0, 0, Imgproc.INTER_CUBIC);
                Mat bigProc = standardPreprocess(big);
                big.release();
                added = runStrategy(gray, "2x", bigProc, allResults);
                consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
            }

            // 策略 8:放大 3x(图很小触发)
            if ((consecutiveEmpty < emptyLimit || allResults.isEmpty()) && Math.min(gray.width(), gray.height()) < 200) {
                Mat big = new Mat();
                Imgproc.resize(gray, big, new Size(gray.width() * 3, gray.height() * 3),
                        0, 0, Imgproc.INTER_CUBIC);
                Mat bigProc = standardPreprocess(big);
                big.release();
                added = runStrategy(gray, "3x", bigProc, allResults);
                consecutiveEmpty = added > 0 ? 0 : consecutiveEmpty + 1;
            }
        } finally {
            gray.release();
            original.release();
        }
        return allResults;
    }

    /**
     * 执行单个识别策略:在预处理后的 Mat 上解码,新增结果到 results。
     *
     * @param ownedInput 当 processed 为 null 时的解码输入(由主流程统一释放,本方法不释放)
     * @param strategy   策略名(用于结果溯源)
     * @param processed  预处理产物 Mat;非 null 时由本方法释放,为 null 则使用 ownedInput
     * @param results    累积结果列表(原地新增)
     * @return 本次策略新增的结果数量
     */
    private int runStrategy(Mat ownedInput, String strategy, Mat processed, List<BarcodeResult> results) {
        int before = results.size();
        Mat input = processed != null ? processed : ownedInput;
        tryDecode(input, strategy, results);
        if (processed != null) {
            processed.release();
        }
        return results.size() - before;
    }

    /* ===================== ZXing 解码（含旋转补偿） ===================== */

    /**
     * 透视矫正：检测标签外轮廓（最大四边形）并拉平为正视图
     *
     * @return 矫正后的 Mat；如果未找到有效四边形则返回 null
     */
    private Mat perspectiveCorrect(Mat gray) {
        // 边缘检测预处理：降噪 + Canny
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

        Mat edges = new Mat();
        Imgproc.Canny(blurred, edges, 50, 150);

        // 膨胀连接断裂边缘
        Mat dilated = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        Imgproc.dilate(edges, dilated, kernel);

        // 查找轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // 筛选最大四边形
        MatOfPoint2f bestQuad = findLargestQuadrilateral(contours, gray.size());

        // 释放资源
        blurred.release();
        edges.release();
        dilated.release();
        kernel.release();
        hierarchy.release();
        for (MatOfPoint c : contours) c.release();

        if (bestQuad == null) {
            return null;
        }

        // 顶点排序：左上、右上、右下、左下
        Point[] sorted = sortCorners(bestQuad.toArray());
        bestQuad.release();

        // 计算目标尺寸（使用四边形长宽的平均值或最大边界）
        double w1 = distance(sorted[0], sorted[1]);
        double w2 = distance(sorted[2], sorted[3]);
        double h1 = distance(sorted[0], sorted[3]);
        double h2 = distance(sorted[1], sorted[2]);
        int maxWidth = (int) Math.max(w1, w2);
        int maxHeight = (int) Math.max(h1, h2);

        // 防止过小或畸形
        if (maxWidth < 100 || maxHeight < 100) {
            return null;
        }

        // 透视变换
        MatOfPoint2f srcPoints = new MatOfPoint2f(sorted);
        MatOfPoint2f dstPoints = new MatOfPoint2f(
                new Point(0, 0),
                new Point(maxWidth - 1, 0),
                new Point(maxWidth - 1, maxHeight - 1),
                new Point(0, maxHeight - 1)
        );

        Mat transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
        Mat warped = new Mat();
        Imgproc.warpPerspective(gray, warped, transform, new Size(maxWidth, maxHeight));

        srcPoints.release();
        dstPoints.release();
        transform.release();

        return warped;
    }

    /**
     * 从轮廓列表中找到面积最大的有效四边形（通常是标签外框）
     */
    private MatOfPoint2f findLargestQuadrilateral(List<MatOfPoint> contours, Size imageSize) {
        MatOfPoint2f bestQuad = null;
        double maxArea = 0;
        double imageArea = imageSize.width * imageSize.height;

        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            // 过滤太小或太大的轮廓
            if (area < imageArea * 0.05 || area > imageArea * 0.95) {
                continue;
            }

            MatOfPoint2f approx = new MatOfPoint2f();
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            double peri = Imgproc.arcLength(contour2f, true);
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true);
            contour2f.release();

            // 必须是四边形
            if (approx.total() == 4) {
                if (area > maxArea && isValidQuadrilateral(approx.toArray())) {
                    if (bestQuad != null) bestQuad.release();
                    bestQuad = (MatOfPoint2f) approx.clone();
                    maxArea = area;
                }
            }
            approx.release();
        }
        return bestQuad;
    }

    /**
     * 验证四边形是否有效：长宽比合理，非退化
     */
    private boolean isValidQuadrilateral(Point[] pts) {
        if (pts.length != 4) return false;
        // 计算四条边长度，确保没有边过短
        double[] lengths = new double[4];
        for (int i = 0; i < 4; i++) {
            lengths[i] = distance(pts[i], pts[(i + 1) % 4]);
            if (lengths[i] < 30) return false; // 边太短，可能是噪点
        }
        // 长宽比检查（标签通常不会太细长）
        double maxSide = Math.max(lengths[0], lengths[2]);
        double minSide = Math.min(lengths[1], lengths[3]);
        double ratio = maxSide / Math.max(minSide, 1);
        return ratio > 0.2 && ratio < 5.0;
    }

    /**
     * 对四边形顶点进行排序：左上、右上、右下、左下
     */
    private Point[] sortCorners(Point[] pts) {
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

    private double distance(Point p1, Point p2) {
        return Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    /**
     * 对给定图像尝试 0°/90°/180°/270° 识别，返回该策略下去重后的结果。
     * 策略内去重使用更严格的阈值（归一化 8%），避免同一条码多角度的重复。
     */
    private void tryDecode(Mat mat, String strategy, List<BarcodeResult> results) {
        BufferedImage image = matToBufferedImage(mat);
        if (image == null) return;

        // 一维码对旋转敏感，尝试 0°、90°、180°、270°
        for (int angle = 0; angle < 360; angle += 90) {
            BufferedImage rotated = rotateImage(image, angle);
            try {
                Result[] found = decodeSingle(rotated);
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
     * 判断新识别结果是否与已有结果重复（同一个物理条码）
     * 条件：码制相同 + 内容相同 + 位置重叠 → 才视为重复
     * 如果内容相同但位置不同，说明是图中不同位置的不同码，不去重
     * <p>
     * 所有坐标已统一为原始图坐标系，可直接比较
     */
    private boolean isDuplicate(String text, String type, ResultPoint[] points, List<BarcodeResult> existing) {
        for (BarcodeResult old : existing) {
            // 码制和内容都必须相同才有可能是重复
            if (!old.type.equals(type) || !old.data.equals(text)) {
                continue;
            }
            // 计算两个结果在原始图坐标系下的中心点距离
            double dist = centerDistance(points, old.points);
            // 阈值取旧结果包围盒对角线的 50%，最小 20px，若距离小于阈值则判定为同一码
            double threshold = boundingBoxDiagonal(old.points) * 0.5;
            if (dist < Math.max(threshold, 20)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算两组点在原始图坐标系下的中心点距离
     */
    private double centerDistance(ResultPoint[] points1, ResultPoint[] points2) {
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
     * 将旋转后图像上的坐标反旋转回原始图坐标系
     * 旋转逻辑与 rotateImage 对应：
     * - 0°:  不变
     * - 90°:  原图 (x,y) → 旋转图 (rotH-y, x)，所以 反旋转 (rx,ry) → (ry, rotW-rx)
     * - 180°: 原图 (x,y) → 旋转图 (rotW-x, rotH-y)，所以 反旋转 (rx,ry) → (rotW-rx, rotH-ry)
     * - 270°: 原图 (x,y) → 旋转图 (y, rotW-x)，所以 反旋转 (rx,ry) → (rotH-ry, rx)
     */
    private ResultPoint[] unrotatePoints(ResultPoint[] points, int rotW, int rotH, int angle) {
        if (angle == 0) return points;

        ResultPoint[] result = new ResultPoint[points.length];
        for (int i = 0; i < points.length; i++) {
            double x = points[i].getX();
            double y = points[i].getY();
            double ox, oy;
            switch (angle) {
                case 90  -> { ox = y;       oy = rotW - x; }
                case 180 -> { ox = rotW - x; oy = rotH - y; }
                case 270 -> { ox = rotH - y; oy = x; }
                default  -> { ox = x;        oy = y; }
            }
            result[i] = new ResultPoint((float) ox, (float) oy);
        }
        return result;
    }

    /**
     * 计算 ResultPoint 包围盒的对角线长度
     */
    private double boundingBoxDiagonal(ResultPoint[] points) {
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

    private Result[] decodeSingle(BufferedImage image) throws NotFoundException {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        return multiReader.decodeMultiple(bitmap, hints);
    }

    /* ===================== OpenCV 预处理策略 ===================== */

    /**
     * 标准预处理：去噪 → CLAHE 对比度增强 → 自适应阈值 → 开运算去噪点 → 锐化
     */
    private Mat standardPreprocess(Mat gray) {
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
     * OTSU 预处理：高斯模糊 → OTSU 自动阈值
     * 适用：整体光照不均、强反光导致条码与背景对比度骤降
     */
    private Mat otsuPreprocess(Mat gray) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

        Mat binary = new Mat();
        Imgproc.threshold(blurred, binary, 0, 255,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

        blurred.release();
        return binary;
    }

    /**
     * 锐化预处理：双边滤波（保边）→ 锐化 → 直方图均衡化
     * 适用：打印模糊、运动模糊、低分辨率打印
     */
    private Mat sharpenPreprocess(Mat gray) {
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

    /* ===================== 工具方法 ===================== */

    private BufferedImage matToBufferedImage(Mat mat) {
        int w = mat.cols(), h = mat.rows(), c = mat.channels();
        byte[] data = new byte[w * h * c];
        mat.get(0, 0, data);

        BufferedImage img;
        if (c == 1) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        } else {
            img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        }
        img.getRaster().setDataElements(0, 0, w, h, data);
        return img;
    }

    private BufferedImage rotateImage(BufferedImage src, int angle) {
        if (angle == 0) return src;

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
        }

        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_BILINEAR);
        return op.filter(src, null);
    }

    /* ===================== 数据模型 ===================== */

    public static class BarcodeResult {
        public final String data;          // 条码内容
        public final String type;          // 码制（QR_CODE, CODE_128 等）
        public final ResultPoint[] points; // 四个角点坐标
        public final String strategy;      // 成功识别的预处理策略
        public final int angle;            // 成功时的旋转角度

        public BarcodeResult(String data, String type, ResultPoint[] points,
                             String strategy, int angle) {
            this.data = data;
            this.type = type;
            this.points = points;
            this.strategy = strategy;
            this.angle = angle;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (strategy=%s, angle=%d°)",
                    type, data, strategy, angle);
        }
    }

    /* ===================== 测试入口 ===================== */

    public static void main(String[] args) {
        try (OpenCVZxingQrReader scanner = new OpenCVZxingQrReader()) {
            String path = "";
            try {
                URL url = Thread.currentThread().getContextClassLoader().getResource("images/2026-05-05_163050.png");
                if (url == null) {
                    throw new IllegalArgumentException("未找到资源");
                }
                // 自动处理编码、特殊字符
                URI uri = url.toURI();
                path = Paths.get(uri).toString();
           /* path = ImageCropAndRotate.processImage(Paths.get(uri).toString());
        } catch (IOException e) {
            e.printStackTrace();*/
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            // 单张难识别标签
            List<BarcodeResult> results = scanner.scan(new File(path));
            System.out.println("共识别到 " + results.size() + " 个唯一条码：");
            results.forEach(System.out::println);

            // 批量处理示例
            // File[] files = new File("/path/to/labels").listFiles();
            // for (File f : files) { ... }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}