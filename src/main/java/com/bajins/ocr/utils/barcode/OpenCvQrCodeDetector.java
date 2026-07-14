package com.bajins.ocr.utils.barcode;

import nu.pattern.OpenCV;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.GraphicalCodeDetector;
import org.opencv.objdetect.QRCodeDetector;
import org.opencv.objdetect.QRCodeDetectorAruco;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 OpenCV QRCodeDetector / QRCodeDetectorAruco 的多二维码识别工具类。
 *
 * <p>核心设计：
 * <ul>
 *   <li>静态工具类：QRCodeDetector 无持久句柄与模型加载，每次扫描局部创建实例即建即释，
 *       无状态静态方法天然线程安全，对齐同目录 ZXingMultiQrReader/ZBarMultiQrReader 风格。</li>
 *   <li>双检测器可切换：{@link DetectorType#QR_CODE} 用标准 QRCodeDetector（快），
 *       {@link DetectorType#QR_CODE_ARUCO} 用 QRCodeDetectorAruco（基于 ArUco 字典的查找器模式检测，
 *       对倾斜/透视变形/反光/弯曲更鲁棒，稍慢；ArUco 参数采用默认值）。
 *       两者均继承 GraphicalCodeDetector，多码解码走父类 detectAndDecodeMulti，
 *       ArUco 增强在 C++ 虚函数分派时生效，故以父类引用统一调用即可。</li>
 *   <li>多码识别：detectAndDecodeMulti 一次调用解码图中所有 QR；解码失败的码（返回空串）被过滤。
 *       多码结果为空时用 detectAndDecode 单码兜底，提升单码场景边缘识别率。</li>
 *   <li>去重：按内容相同 + 角点中心距离 &lt; 旧结果包围盒对角线 50%（最小 20px）判定为同一物理码，
 *       消除 multi 自身多检与 single 兜底的重复。</li>
 *   <li>预处理：默认喂原图（OpenCV 内部转灰度），可选 scale 放大小码进入检测敏感区间；
 *       严禁二值化/锐化——会破坏 QR finder pattern 的 1:1:3:1:1 比例（参考 BoofCvMultiQrReader 注释）。
 *       放大后角点坐标按 1/scale 还原至原图坐标系。</li>
 *   <li>输入支持 BufferedImage/File/Mat 三种重载，OpenCV 原生吃 Mat 最自然。</li>
 * </ul>
 *
 * <p>资源管理：所有中间 Mat（放大图、角点矩阵）在方法内显式 release，防止 native 内存泄漏；
 *   调用方传入的 Mat 由调用方拥有，本类不释放。
 *
 * <p>异常体系：
 * <ul>
 *   <li>参数非法（空图像/空 Mat/不存在文件/scale&lt;1）抛 IllegalArgumentException。</li>
 *   <li>图像读取失败抛 IllegalStateException（包装受检异常）。</li>
 *   <li>未识别到任何二维码（检测返回 false 或全解码失败）视为正常，返回空列表，不抛异常。</li>
 *   <li>OpenCV 原生库缺失在类初始化时抛 ExceptionInInitializerError（系统级，不封装）。</li>
 * </ul>
 *
 * <p>局限：OpenCV QRCodeDetector 仅识别 QR 码，不支持一维码/DataMatrix/Aztec；
 *   定位为"QR 专用高速检测器"，与同包 ZXing/ZBar/BoofCv（支持多码制）互补。
 *
 * <p>依赖：org.openpnp:opencv（提供 org.opencv.* 官方 Java 绑定 + nu.pattern.OpenCV 原生库加载）
 * <p>参考：https://docs.opencv.org/4.9.0/d5/d04/classcv_1_1QRCodeDetector.html
 */
public class OpenCvQrCodeDetector {

    /**
     * 去重时中心点距离的最小阈值（px），避免包围盒过小时误判
     */
    private static final double DUP_MIN_DIST = 20.0;
    /**
     * 去重距离阈值占旧结果包围盒对角线的比例
     */
    private static final double DUP_DIST_RATIO = 0.5;
    /**
     * 默认配置单例（不可变，安全共享）：标准 QRCodeDetector，不放大
     */
    private static final Options DEFAULT_OPTIONS = new Options(DetectorType.QR_CODE, 1);

    static {
        // 类初始化时加载对应平台 OpenCV 原生库（无需系统安装），库缺失抛 ExceptionInInitializerError
        OpenCV.loadLocally();
    }

    /**
     * 识别图像中的所有二维码（默认配置：标准 QRCodeDetector，不放大）。
     *
     * @param image 源图像，可为彩色或灰度，内部转 Mat 后交 OpenCV 处理
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException image 为 null
     */
    public static List<Barcode> detect(BufferedImage image) {
        return detect(image, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图像中的所有二维码。
     *
     * @param image   源图像，可为彩色或灰度
     * @param options 识别配置，决定检测器类型与放大倍数
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException image 或 options 为 null
     */
    public static List<Barcode> detect(BufferedImage image, Options options) {
        Objects.requireNonNull(image, "待识别图像不能为空");
        Objects.requireNonNull(options, "识别配置不能为空");
        Mat mat = bufferedToMat(image);
        try {
            return scan(mat, options);
        } finally {
            mat.release();
        }
    }

    /**
     * 识别图片文件中的所有二维码（默认配置）。
     *
     * @param file 图片文件，须存在且可读
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<Barcode> detect(File file) {
        return detect(file, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图片文件中的所有二维码。
     *
     * @param file    图片文件，须存在且可读
     * @param options 识别配置
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException file 为 null 或不存在，或 options 为 null
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<Barcode> detect(File file, Options options) {
        Objects.requireNonNull(file, "图片文件不能为空");
        Objects.requireNonNull(options, "识别配置不能为空");
        Mat mat = readMat(file);
        try {
            return scan(mat, options);
        } finally {
            mat.release();
        }
    }

    /**
     * 识别 OpenCV Mat 中的所有二维码（默认配置）。
     *
     * @param mat 源图像 Mat（不会被修改，由调用方负责释放）
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException mat 为 null 或为空 Mat
     */
    public static List<Barcode> detect(Mat mat) {
        return detect(mat, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别 OpenCV Mat 中的所有二维码。
     * <p>传入的 Mat 由调用方拥有，本方法不释放（内部放大产物自行释放）。
     *
     * @param mat     源图像 Mat（不会被修改，由调用方负责释放）
     * @param options 识别配置
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException mat 为 null 或为空 Mat，或 options 为 null
     */
    public static List<Barcode> detect(Mat mat, Options options) {
        Objects.requireNonNull(mat, "待识别图像不能为空");
        Objects.requireNonNull(options, "识别配置不能为空");
        if (mat.empty()) {
            throw new IllegalArgumentException("待识别图像为空 Mat");
        }
        return scan(mat, options);
    }

    /**
     * 识别图像中所有二维码的文本内容（默认配置）。
     *
     * @param image 源图像
     * @return 二维码文本列表，顺序与 {@link #detect(BufferedImage)} 一致
     * @throws IllegalArgumentException image 为 null
     */
    public static List<String> decode(BufferedImage image) {
        return decode(image, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图像中所有二维码的文本内容。
     *
     * @param image   源图像
     * @param options 识别配置
     * @return 二维码文本列表，顺序与 {@link #detect(BufferedImage, Options)} 一致
     * @throws IllegalArgumentException image 或 options 为 null
     */
    public static List<String> decode(BufferedImage image, Options options) {
        return toTexts(detect(image, options));
    }

    /**
     * 识别图片文件中所有二维码的文本内容（默认配置）。
     *
     * @param file 图片文件
     * @return 二维码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<String> decode(File file) {
        return decode(file, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图片文件中所有二维码的文本内容。
     *
     * @param file    图片文件
     * @param options 识别配置
     * @return 二维码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在，或 options 为 null
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<String> decode(File file, Options options) {
        return toTexts(detect(file, options));
    }

    /**
     * 识别 OpenCV Mat 中所有二维码的文本内容（默认配置）。
     *
     * @param mat 源图像 Mat（由调用方负责释放）
     * @return 二维码文本列表
     * @throws IllegalArgumentException mat 为 null 或为空 Mat
     */
    public static List<String> decode(Mat mat) {
        return decode(mat, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别 OpenCV Mat 中所有二维码的文本内容。
     *
     * @param mat     源图像 Mat（由调用方负责释放）
     * @param options 识别配置
     * @return 二维码文本列表
     * @throws IllegalArgumentException mat 为 null 或为空 Mat，或 options 为 null
     */
    public static List<String> decode(Mat mat, Options options) {
        return toTexts(detect(mat, options));
    }

    /**
     * 提取结果列表的文本内容。
     *
     * @param barcodes 识别结果列表
     * @return 文本列表，顺序与入参一致
     */
    private static List<String> toTexts(List<Barcode> barcodes) {
        List<String> texts = new ArrayList<>(barcodes.size());
        for (Barcode b : barcodes) {
            texts.add(b.text());
        }
        return texts;
    }

    /**
     * 执行一次完整扫描：可选放大 -> 创建检测器 -> 多码检测 -> 单码兜底 -> 角点还原 -> 去重。
     *
     * @param source  源图像 Mat（由调用方负责释放，本方法不释放）
     * @param options 识别配置
     * @return 去重后的二维码结果列表（可能为空，但不为 null）
     */
    private static List<Barcode> scan(Mat source, Options options) {
        // 可选放大：放大产物自行释放，原图由调用方拥有
        Mat img = source;
        Mat scaled = null;
        // 角点还原系数：放大后检测坐标需乘 1/scale 回到原图坐标系
        double invScale = 1.0;
        if (options.scale() > 1) {
            scaled = scaleMat(source, options.scale());
            img = scaled;
            invScale = 1.0 / options.scale();
        }

        GraphicalCodeDetector detector = createDetector(options);
        String detectorName = options.detectorType().name();
        List<Barcode> results = new ArrayList<>();
        try {
            // 多码检测：一次调用解码图中所有 QR
            detectMulti(detector, img, invScale, detectorName, results);
            // 单码兜底：multi 未识别到任何码时尝试单码解码，提升单码场景边缘识别率
            if (results.isEmpty()) {
                detectSingle(detector, img, invScale, detectorName, results);
            }
        } finally {
            if (scaled != null) {
                scaled.release();
            }
        }
        // 去重：消除 multi 自身多检与 single 兜底的重复
        return dedup(results);
    }

    /**
     * 按配置创建检测器实例（局部创建，方法结束即释，线程安全）。
     *
     * @param options 识别配置，决定检测器类型
     * @return 检测器实例（以父类 GraphicalCodeDetector 引用，统一调用多码 API）
     */
    private static GraphicalCodeDetector createDetector(Options options) {
        return switch (options.detectorType()) {
            case QR_CODE -> new QRCodeDetector();
            case QR_CODE_ARUCO -> new QRCodeDetectorAruco();
        };
    }

    /**
     * 多码检测：调用 detectAndDecodeMulti 一次解码图中所有 QR，角点按 invScale 还原至原图坐标系。
     * <p>decoded_info 每元素对应一个码，空串表示检测到但解码失败，予以过滤。
     *
     * @param detector     检测器实例
     * @param img          待检测图像（原图或放大图）
     * @param invScale     角点还原系数（1/scale），放大图坐标乘此值回到原图坐标
     * @param detectorName 检测器名称（用于结果溯源）
     * @param results      累积结果列表（原地新增）
     */
    private static void detectMulti(GraphicalCodeDetector detector, Mat img, double invScale,
                                    String detectorName, List<Barcode> results) {
        Mat points = new Mat();
        List<String> decoded = new ArrayList<>();
        try {
            boolean detected = detector.detectAndDecodeMulti(img, decoded, points);
            // 未检测到任何 QR 属正常情况，直接返回
            if (!detected || decoded.isEmpty()) {
                return;
            }
            // points 为 CV_32FC2，每码 4 个角点按码顺序串联：total = 码数 × 4
            int count = (int) (points.total() * points.channels());
            if (count < 8) {
                return;
            }
            float[] data = new float[count];
            points.get(0, 0, data);
            // 按数据长度收紧码数上限，防止 OpenCV 返回的角点数与 decoded 数不匹配时越界
            int codeCount = Math.min(decoded.size(), data.length / 8);
            for (int i = 0; i < codeCount; i++) {
                String text = decoded.get(i);
                // 跳过检测到但解码失败的码（空串）
                if (text == null || text.isEmpty()) {
                    continue;
                }
                List<Point> corners = new ArrayList<>(4);
                for (int j = 0; j < 4; j++) {
                    int idx = (i * 4 + j) * 2;
                    corners.add(new Point(data[idx] * invScale, data[idx + 1] * invScale));
                }
                results.add(new Barcode(text, List.copyOf(corners), detectorName));
            }
        } finally {
            points.release();
        }
    }

    /**
     * 单码兜底：调用 detectAndDecode 解码图中首个 QR，作为 multi 漏检时的补充。
     *
     * @param detector     检测器实例
     * @param img          待检测图像
     * @param invScale     角点还原系数
     * @param detectorName 检测器名称
     * @param results      累积结果列表（原地新增）
     */
    private static void detectSingle(GraphicalCodeDetector detector, Mat img, double invScale,
                                     String detectorName, List<Barcode> results) {
        Mat points = new Mat();
        try {
            String text = detector.detectAndDecode(img, points);
            // 空串表示未检测到或解码失败
            if (text == null || text.isEmpty()) {
                return;
            }
            int count = (int) (points.total() * points.channels());
            if (count < 2) {
                // 极端情况：解码成功但无角点，仍返回内容
                results.add(new Barcode(text, List.of(), detectorName));
                return;
            }
            float[] data = new float[count];
            points.get(0, 0, data);
            List<Point> corners = new ArrayList<>();
            for (int j = 0; j < count / 2; j++) {
                corners.add(new Point(data[j * 2] * invScale, data[j * 2 + 1] * invScale));
            }
            results.add(new Barcode(text, List.copyOf(corners), detectorName));
        } finally {
            points.release();
        }
    }

    /**
     * 对结果列表去重：内容相同且角点中心距离接近视为同一物理码。
     *
     * @param results 原始结果列表（可能含重复）
     * @return 去重后的列表（保持首次出现顺序）
     */
    private static List<Barcode> dedup(List<Barcode> results) {
        List<Barcode> unique = new ArrayList<>(results.size());
        for (Barcode b : results) {
            if (!isDuplicate(b, unique)) {
                unique.add(b);
            }
        }
        return unique;
    }

    /**
     * 判断新结果是否与已有结果重复：内容相同 + 中心点距离 &lt; 阈值。
     * <p>阈值取旧结果包围盒对角线的 {@value #DUP_DIST_RATIO}，最小 {@value #DUP_MIN_DIST}px。
     *
     * @param b        新结果
     * @param existing 已有结果列表
     * @return 重复返回 true，否则 false
     */
    private static boolean isDuplicate(Barcode b, List<Barcode> existing) {
        for (Barcode e : existing) {
            // 内容不同必定不是同一码
            if (!e.text().equals(b.text())) {
                continue;
            }
            double dist = centerDistance(b.corners(), e.corners());
            double threshold = Math.max(boundingBoxDiagonal(e.corners()) * DUP_DIST_RATIO, DUP_MIN_DIST);
            if (dist < threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算两组角点的中心点欧氏距离。
     *
     * @param p1 第一组角点
     * @param p2 第二组角点
     * @return 中心点距离；任一组为空返回 Double.MAX_VALUE（不判定为重复）
     */
    private static double centerDistance(List<Point> p1, List<Point> p2) {
        if (p1.isEmpty() || p2.isEmpty()) {
            return Double.MAX_VALUE;
        }
        return Math.sqrt(Math.pow(centerX(p1) - centerX(p2), 2) + Math.pow(centerY(p1) - centerY(p2), 2));
    }

    /**
     * 计算角点包围盒的对角线长度。
     *
     * @param points 角点列表
     * @return 对角线长度；为空返回 0
     */
    private static double boundingBoxDiagonal(List<Point> points) {
        if (points.isEmpty()) {
            return 0;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point p : points) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        return Math.sqrt(Math.pow(maxX - minX, 2) + Math.pow(maxY - minY, 2));
    }

    /**
     * 计算角点组的 X 方向中心。
     *
     * @param points 角点列表（须非空）
     * @return X 中心
     */
    private static double centerX(List<Point> points) {
        double sum = 0;
        for (Point p : points) {
            sum += p.x;
        }
        return sum / points.size();
    }

    /**
     * 计算角点组的 Y 方向中心。
     *
     * @param points 角点列表（须非空）
     * @return Y 中心
     */
    private static double centerY(List<Point> points) {
        double sum = 0;
        for (Point p : points) {
            sum += p.y;
        }
        return sum / points.size();
    }

    /**
     * 放大 Mat（scale&gt;1），双三次插值保持清晰度。
     *
     * @param src   原始 Mat（由调用方拥有，不释放）
     * @param scale 放大倍数（&gt;1）
     * @return 放大后的新 Mat（由调用方释放）
     */
    private static Mat scaleMat(Mat src, int scale) {
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(src.cols() * scale, src.rows() * scale),
                0, 0, Imgproc.INTER_CUBIC);
        return dst;
    }

    /**
     * 将 BufferedImage 转为 OpenCV Mat（8UC3 BGR）。
     * <p>统一绘制到 3BYTE_BGR 缓冲图后取 raster 原始字节，字节序恰为 BGR，与 OpenCV 8UC3 一致。
     *
     * @param img 原始图像，可为任意颜色类型
     * @return 8UC3 BGR Mat（由调用方释放）
     */
    private static Mat bufferedToMat(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage bgr;
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            bgr = img;
        } else {
            // 非 3BYTE_BGR 先统一绘制到 3BYTE_BGR，保证字节序为 BGR
            bgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgr.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(h, w, CvType.CV_8UC3);
        mat.put(0, 0, pixels);
        return mat;
    }

    /**
     * 用 OpenCV 原生读取图片文件为 Mat。
     *
     * @param file 图片文件，须存在且可读
     * @return 解码后的 Mat（由调用方释放）
     * @throws IllegalArgumentException 文件不存在或不是文件
     * @throws IllegalStateException    读取失败或格式不支持
     */
    private static Mat readMat(File file) {
        if (!file.isFile()) {
            throw new IllegalArgumentException("图片文件不存在或不是文件: " + file);
        }
        Mat mat = Imgcodecs.imread(file.getAbsolutePath());
        if (mat.empty()) {
            mat.release();
            throw new IllegalStateException("无法读取图片(格式不支持或文件损坏): " + file);
        }
        return mat;
    }

    /**
     * 检测器类型枚举。
     */
    public enum DetectorType {
        /**
         * 标准 QRCodeDetector：速度快，适合常规清晰二维码
         */
        QR_CODE,
        /**
         * QRCodeDetectorAruco：基于 ArUco 字典的查找器模式检测，对倾斜/透视/反光/弯曲更鲁棒，稍慢
         */
        QR_CODE_ARUCO
    }

    /**
     * 单个二维码识别结果（不可变）。
     *
     * @param text     解码内容文本（非空）
     * @param corners  四个角点坐标（原图坐标系，已按放大倍数还原；可能为空）
     * @param detector 命中时所用的检测器名称（DetectorType 枚举名）
     */
    public record Barcode(String text, List<Point> corners, String detector) {
        @Override
        public String toString() {
            return String.format("[%s] %s (corners=%s)", detector, text, corners);
        }
    }

    /**
     * 识别配置（不可变），持有检测器类型与放大倍数。
     */
    public static final class Options {

        private final DetectorType detectorType;
        private final int scale;

        private Options(DetectorType detectorType, int scale) {
            this.detectorType = detectorType;
            this.scale = scale;
        }

        /**
         * 默认配置：标准 QRCodeDetector，不放大。
         *
         * @return 默认配置
         */
        public static Options defaultConfig() {
            return DEFAULT_OPTIONS;
        }

        /**
         * ArUco 配置：QRCodeDetectorAruco，不放大。适合倾斜/反光等恶劣条件。
         *
         * @return ArUco 配置
         */
        public static Options aruco() {
            return new Options(DetectorType.QR_CODE_ARUCO, 1);
        }

        /**
         * 指定检测器类型的配置（不放大）。
         *
         * @param type 检测器类型，不可为 null
         * @return 新配置
         * @throws NullPointerException type 为 null
         */
        public static Options of(DetectorType type) {
            Objects.requireNonNull(type, "检测器类型不能为空");
            return new Options(type, 1);
        }

        /**
         * 切换检测器类型，返回新配置（原配置不变）。
         *
         * @param type 检测器类型，不可为 null
         * @return 新配置
         * @throws NullPointerException type 为 null
         */
        public Options detector(DetectorType type) {
            Objects.requireNonNull(type, "检测器类型不能为空");
            return new Options(type, this.scale);
        }

        /**
         * 设置放大倍数，返回新配置（原配置不变）。
         *
         * @param scale 放大倍数，须 &gt;= 1；小码可调大使其进入检测敏感区间
         * @return 新配置
         * @throws IllegalArgumentException scale &lt; 1
         */
        public Options scale(int scale) {
            if (scale < 1) {
                throw new IllegalArgumentException("放大倍数必须 >= 1: " + scale);
            }
            return new Options(this.detectorType, scale);
        }

        /**
         * @return 检测器类型
         */
        public DetectorType detectorType() {
            return detectorType;
        }

        /**
         * @return 放大倍数
         */
        public int scale() {
            return scale;
        }
    }

    /**
     * 测试入口：从 classpath 读取示例图片，分别用标准与 ArUco 检测器识别并输出对比。
     *
     * @param args 未使用
     * @throws URISyntaxException 示例资源 URI 解析失败
     */
    public static void main(String[] args) throws URISyntaxException {
        URL url = Thread.currentThread().getContextClassLoader().getResource("images/2026-05-05_163050.png");
        if (url == null) {
            throw new IllegalArgumentException("未找到示例图片资源 images/2026-05-05_163050.png");
        }
        File file = new File(url.toURI());

        System.out.println("==== 标准检测器 (QRCodeDetector) ====");
        printResults(detect(file));

        System.out.println("==== ArUco 检测器 (QRCodeDetectorAruco) ====");
        printResults(detect(file, Options.aruco()));
    }

    /**
     * 打印识别结果。
     *
     * @param results 结果列表
     */
    private static void printResults(List<Barcode> results) {
        System.out.println("共识别到 " + results.size() + " 个二维码：");
        for (Barcode b : results) {
            System.out.println("----------------------");
            System.out.println("检测器: " + b.detector());
            System.out.println("内容  : " + b.text());
            System.out.println("角点  : " + b.corners());
        }
    }
}
