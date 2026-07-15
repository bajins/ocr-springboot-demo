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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

/**
 * OpenCV + ZXing 增强型条码识别器（形态学切割 + 单码识别为主，多策略整图兜底）。
 * <p>
 * <b>主流程：
 * 1. 对图片进行预处理：转灰度图 -> 图像二值化（或边缘检测） -> 膨胀操作（把条码的密集像素连成一个个实心的矩形块）。
 * 2. 调用 `Imgproc.findContours` 找到每一个实心块的边界框（`Rect`）。
 * 3. 根据这个 `Rect`，把原图上的条码一个个 `crop`（裁剪）下来。
 * 4. 将这些裁剪好的小图，逐一送给 ZXing 的单码识别 `MultiFormatReader.decode()`。
 * <p>
 * ZXing 的 {@code decodeMultiple} 对一整张含几十个码的图很弱（密集码互相干扰、漏扫、相同内容去重失误）。
 * 故先用 OpenCV 形态学把每个条码/二维码连成"实心块"，裁剪成只含一个码的干净小图，再单码 {@code decode}，
 * 既不漏又天然携带精确 (x,y) 坐标供分行排序。
 * <p>
 * | 步骤         | 操作                                            | 目地                              |
 * | ---------- | --------------------------------------------- | ------------------------------- |
 * | **边缘检测**   | `GaussianBlur` + `Canny`                      | 凸显条码/二维码密集的黑白交错边缘              |
 * | **形态学闭运算** | `MORPH_CLOSE` 方核（核尺寸按短边自适应）                  | 把密集边缘连成实心块：二维码整体成块、一维码竖条聚合     |
 * | **轮廓定位**   | `findContours` + `boundingRect`               | 拿到每个码的精确外接 Rect (x,y,w,h)      |
 * | **候选过滤**   | 尺寸/面积比/宽高比过滤 + IOU 合并重叠 + padding 扩边          | 剔除噪点/整图背景/细长条纹，保留 Quiet Zone    |
 * | **逐块解码**   | 裁剪小图 -> 单码 `decode`（4 角度）-> 失败 `decodeMultiple` 兜底 | 一图一码不漏；粘连多码由多码兜底捕获              |
 * | **坐标还原**   | 角点反旋转回 0° + 叠加区域偏移                            | 还原到原图坐标系，供跨阶段去重与分行排序            |
 * <p>
 * <b>兜底流程：整图多策略 + 透视矫正 + 放大</b>（捕获切割遗漏：粘连二维码、无边缘特征码、切割失败）：
 * <p>
 * 切割主流程识别后，仍跑一遍整图多码扫描作为兜底，靠位置去重与切割结果合并。早停机制保证已有结果时快速跳过重策略。
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
 *       形态学切割中间产物由 {@code locateBarcodeRegions} 释放，裁剪 ROI 由 {@code decodeRegions} 释放；
 *       各预处理策略产出的 Mat 由 {@code runStrategy} 释放，基图（gray/warped）由各自的拥有方释放。</li>
 *   <li>线程安全：ZXing {@code GenericMultipleBarcodeReader} 与 {@code MultiFormatReader} 均非线程安全，
 *       {@link #scanInternal} 以实例锁串行化；高并发场景请为每个线程创建独立实例，而非放开锁。</li>
 *   <li>坐标体系统一：切割路径的 {@code rect} 用切割框（精确外框），整图兜底路径用角点包围盒；
 *       所有 {@code points} 已反旋转 + 叠加偏移回原图坐标系，可直接用于去重与排序。</li>
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
    /**
     * 形态学切割：候选区域最小宽高（px），小于此视为噪点
     */
    private static final int MIN_REGION_SIZE = 30;
    /**
     * 形态学切割：候选区域占图面积比上限，超过视为整图背景
     */
    private static final double MAX_REGION_RATIO = 0.9;
    /**
     * 形态学切割：候选区域宽高比下限
     */
    private static final double ASPECT_REGION_MIN = 0.15;
    /**
     * 形态学切割：候选区域宽高比上限
     */
    private static final double ASPECT_REGION_MAX = 15.0;
    /**
     * 形态学切割：裁剪扩边（px），保留 Quiet Zone 提升 ZXing 识别率
     */
    private static final int REGION_PADDING = 8;
    /**
     * 形态学切割：合并重叠候选区域的 IOU 阈值
     */
    private static final double MERGE_IOU = 0.3;
    /**
     * 形态学核尺寸：短边比例分母，核大小 ≈ minDim / {@value}
     */
    private static final int KERNEL_SIZE_DIVISOR = 40;
    /**
     * 形态学核尺寸下限（px，奇数）
     */
    private static final int KERNEL_MIN = 7;
    /**
     * 形态学核尺寸上限（px，奇数）
     */
    private static final int KERNEL_MAX = 31;
    /**
     * 分行排序：Y 方向最小容差（px），防止极小码容差为 0 导致行错乱
     */
    private static final int MIN_ROW_TOLERANCE = 15;

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
    /**
     * 单码读取器：形态学切割后逐块单码 {@code decode} 使用（一图一码假设，速度快不漏码）
     */
    private final MultiFormatReader singleReader;
    private final Map<DecodeHintType, Object> hints;

    /**
     * 构造识别器：初始化 ZXing 多码/单码读取器与解码提示（限定码制 + TRY_HARDER）。
     */
    public OpenCVZxingQrReader() {
        MultiFormatReader formatReader = new MultiFormatReader();
        multiReader = new GenericMultipleBarcodeReader(formatReader);
        singleReader = new MultiFormatReader();

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
     * @return 去重后的全部识别结果（未排序，可能为空，但不为 null）
     * @throws IOException 图片不存在或无法解码时抛出
     */
    public List<BarcodeResult> scan(File imageFile) throws IOException {
        Mat src = readMat(imageFile);
        // scanInternal 在 finally 中释放 src，调用方无需再释放
        return scanInternal(src);
    }

    /**
     * 识别图片文件中的所有条码，并按从上到下、从左到右分行排序返回。
     *
     * @param imageFile 待识别的图片文件
     * @return 去重并按坐标分行排序后的全部识别结果（可能为空，但不为 null）
     * @throws IOException 图片不存在或无法解码时抛出
     */
    public List<BarcodeResult> scanOrdered(File imageFile) throws IOException {
        return sortByRow(scan(imageFile));
    }

    /**
     * 读取图片文件为 Mat（中文路径免疫）。
     *
     * <p>OpenCV {@code imread} 在 Windows 下经 C 运行时 {@code fopen}（ANSI 编码）读取路径，含非 ASCII
     * 字符（如中文）的路径会因编码错配而失败。故先用 Java NIO 读字节再 {@code imdecode} 绕过 native
     * {@code fopen}；{@code imdecode} 失败（OpenCV 不支持的格式）时用 {@code ImageIO} 兜底转 Mat。
     *
     * @param file 图片文件，须存在且可读
     * @return 解码后的 Mat（由调用方释放）
     * @throws IOException 图片读取失败
     */
    private static Mat readMat(File file) throws IOException {
        if (!file.isFile()) {
            throw new IllegalArgumentException("图片文件不存在或不是文件: " + file);
        }
        // A: imdecode 优先--绕过 native fopen，免疫中文路径，保留 OpenCV 全格式解码能力
        byte[] data = Files.readAllBytes(file.toPath());
        Mat buf = new MatOfByte(data);
        Mat mat = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR);
        buf.release();
        if (!mat.empty()) {
            return mat;
        }
        mat.release();
        // B: ImageIO 兜底--OpenCV imdecode 不支持的格式（如部分 CMYK JPEG），由 ImageIO 解码后转 Mat
        BufferedImage bi = ImageIO.read(file);
        if (bi == null) {
            throw new IOException("无法读取图片(格式不支持或文件损坏): " + file);
        }
        return bufferedToMat(bi);
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
     * 直接识别 OpenCV Mat。内部克隆保护原图，调用方后续需自行释放传入的 Mat。
     *
     * @param src 待识别的 Mat（不会被修改，由调用方负责释放）
     * @return 去重后的全部识别结果（未排序，可能为空，但不为 null）
     */
    public List<BarcodeResult> scan(Mat src) {
        // 克隆一份：scanInternal 会释放传入的 Mat，故隔离以保护调用方的原图
        return scanInternal(src.clone());
    }

    /**
     * 直接识别 OpenCV Mat，并按从上到下、从左到右分行排序返回。
     *
     * @param src 待识别的 Mat（不会被修改，由调用方负责释放）
     * @return 去重并按坐标分行排序后的全部识别结果（可能为空，但不为 null）
     */
    public List<BarcodeResult> scanOrdered(Mat src) {
        return sortByRow(scan(src));
    }

    @Override
    public void close() {
        // ZXing 的 reader 无显式 close，依赖 GC 即可
    }

    /**
     * 核心识别管道：阶段一形态学切割逐块单码识别（主流程）+ 阶段二整图多策略兜底。
     * <p>阶段一用 OpenCV 形态学把每个码连成实心块，裁剪后单码 decode，拿精确坐标；
     * 阶段二在灰度图上依次执行多套预处理策略（每策略 4 角度旋转补偿），命中后早停，
     * 捕获切割遗漏的码。两阶段结果按位置去重后汇总返回。
     * <p>实例锁串行化，保护非线程安全的 {@code multiReader} 与 {@code singleReader}。
     *
     * @param original 源图像 Mat（由本方法在 finally 中释放，调用方无需再释放）
     * @return 去重后的全部识别结果（未排序，可能为空，但不为 null）
     */
    private synchronized List<BarcodeResult> scanInternal(Mat original) {
        List<BarcodeResult> allResults = new ArrayList<>();

        // 统一转灰度作为切割检测、裁剪与所有策略的输入
        Mat gray = new Mat();
        try {
            if (original.channels() == 3 || original.channels() == 4) {
                Imgproc.cvtColor(original, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                original.copyTo(gray);
            }

            // 阶段一：形态学切割 + 逐块单码识别（主流程，拿精确坐标）
            List<Rect> regions = locateBarcodeRegions(gray);
            decodeRegions(gray, regions, allResults);

            /*
             * 阶段二：整图多策略兜底（捕获切割遗漏：粘连二维码、无边缘特征码、切割失败）。
             * 早停：已识别到码 且 连续 EMPTY_LIMIT 个策略无新增 -> 跳过后续重策略（透视矫正/放大）。
             * 阶段一已产出结果时，阶段二通常前几个轻量策略即可早停；若阶段一空手而归（allResults 为空），
             * 即便连续无新增也继续尝试救命策略，避免倾斜/极小标签因前几个增强策略未命中而被漏识。
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
            // 整图兜底：bounds=null 表示无区域偏移，rect 用角点包围盒
            tryDecode(input, strategy, null, results);
        } finally {
            // processed 由本策略创建，无论解码成功或抛异常都必须释放；ownedInput 由主流程释放
            if (processed != null) {
                processed.release();
            }
        }
        return results.size() - before;
    }

    /**
     * 形态学切割定位条码候选区域：Canny 边缘 -> 方核闭运算连块 -> 轮廓过滤 -> IOU 合并 -> 扩边。
     * <p>方核对二维码（整体成块）与一维码（竖条聚合）均适用；核尺寸按图像短边自适应，避免固定尺寸
     * 在不同分辨率下粘连（核过大）或连不上（核过小）。
     *
     * @param gray 输入灰度图
     * @return 候选区域 Rect 列表（已合并重叠、扩边，原图坐标系）
     */
    private static List<Rect> locateBarcodeRegions(Mat gray) {
        List<Rect> regions = new ArrayList<>();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat closed = new Mat();
        Mat hierarchy = new Mat();
        Mat kernel = null;
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            // 轻度高斯降噪，让 Canny 边缘更连续
            Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);
            // Canny 边缘检测：凸显条码/二维码密集的黑白交错边缘
            Imgproc.Canny(blurred, edges, 50, 150);
            // 方核闭运算：把密集边缘连成实心块（二维码整体成块、一维码竖条聚合）
            int k = adaptiveKernelSize(gray);
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k));
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
            // 查找最外层轮廓，压缩水平/垂直/对角线段以减少点数
            Imgproc.findContours(closed, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double imgArea = gray.cols() * (double) gray.rows();
            Size size = gray.size();
            for (MatOfPoint contour : contours) {
                Rect r = Imgproc.boundingRect(contour);
                // 过滤噪点（过小）、整图背景（过大）、异常宽高比（细长条纹/图像边界）
                if (r.width < MIN_REGION_SIZE || r.height < MIN_REGION_SIZE) {
                    continue;
                }
                if (r.width * (double) r.height > imgArea * MAX_REGION_RATIO) {
                    continue;
                }
                double aspect = r.width / (double) r.height;
                if (aspect < ASPECT_REGION_MIN || aspect > ASPECT_REGION_MAX) {
                    continue;
                }
                regions.add(padRect(r, size));
            }
        } finally {
            // 中间产物无论成功或异常都释放，避免 native 内存泄漏
            blurred.release();
            edges.release();
            closed.release();
            hierarchy.release();
            if (kernel != null) {
                kernel.release();
            }
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
        // 合并高度重叠的候选区，避免同一物理码被多轮廓重复框选
        return mergeOverlappingRegions(regions);
    }

    /**
     * 形态学核尺寸自适应：按图像短边比例缩放，限制在 [{@value #KERNEL_MIN}, {@value #KERNEL_MAX}] 且为奇数。
     *
     * @param gray 输入灰度图
     * @return 奇数核尺寸（px）
     */
    private static int adaptiveKernelSize(Mat gray) {
        int minDim = Math.min(gray.width(), gray.height());
        int k = minDim / KERNEL_SIZE_DIVISOR;
        // 强制奇数：形态学核标准做法，保证明确的中心点
        if (k % 2 == 0) {
            k++;
        }
        return Math.max(KERNEL_MIN, Math.min(KERNEL_MAX, k));
    }

    /**
     * 对候选 Rect 向外扩边（保留 Quiet Zone），并裁剪到图像边界内。
     *
     * @param r         原始候选矩形
     * @param imageSize 原图尺寸
     * @return 扩边并边界裁剪后的 Rect
     */
    private static Rect padRect(Rect r, Size imageSize) {
        int x = Math.max(0, r.x - REGION_PADDING);
        int y = Math.max(0, r.y - REGION_PADDING);
        // 宽高基于已 clamp 的 x/y 计算，保证不越界
        int w = Math.min((int) imageSize.width - x, r.width + 2 * REGION_PADDING);
        int h = Math.min((int) imageSize.height - y, r.height + 2 * REGION_PADDING);
        return new Rect(x, y, w, h);
    }

    /**
     * 合并高度重叠的候选矩形：IOU 超过 {@value #MERGE_IOU} 的合并为外接矩形。
     * <p>同一物理条码可能被多个轮廓框选，合并后避免重复解码。
     *
     * @param regions 待合并的候选矩形列表
     * @return 合并后的矩形列表（新建列表）
     */
    private static List<Rect> mergeOverlappingRegions(List<Rect> regions) {
        if (regions.size() <= 1) {
            return new ArrayList<>(regions);
        }
        List<Rect> merged = new ArrayList<>();
        // 标记已合并的矩形，避免重复处理
        boolean[] used = new boolean[regions.size()];
        for (int i = 0; i < regions.size(); i++) {
            if (used[i]) {
                continue;
            }
            Rect current = regions.get(i);
            used[i] = true;
            for (int j = i + 1; j < regions.size(); j++) {
                if (used[j]) {
                    continue;
                }
                Rect other = regions.get(j);
                if (iou(current, other) > MERGE_IOU) {
                    // 合并为两矩形的外接矩形
                    int x = Math.min(current.x, other.x);
                    int y = Math.min(current.y, other.y);
                    int w = Math.max(current.x + current.width, other.x + other.width) - x;
                    int h = Math.max(current.y + current.height, other.y + other.height) - y;
                    current = new Rect(x, y, w, h);
                    used[j] = true;
                }
            }
            merged.add(current);
        }
        return merged;
    }

    /**
     * 计算两矩形的 IOU（交并比）。
     *
     * @param a 第一矩形
     * @param b 第二矩形
     * @return IOU 值 [0,1]；无重叠返回 0
     */
    private static double iou(Rect a, Rect b) {
        int intersectX = Math.max(a.x, b.x);
        int intersectY = Math.max(a.y, b.y);
        int intersectW = Math.min(a.x + a.width, b.x + b.width) - intersectX;
        int intersectH = Math.min(a.y + a.height, b.y + b.height) - intersectY;
        // 无交集直接返回 0
        if (intersectW <= 0 || intersectH <= 0) {
            return 0.0;
        }
        double intersectArea = intersectW * (double) intersectH;
        // 并集面积 = 两矩形面积之和 - 交集面积（扣除重复计算的交集部分）
        double unionArea = a.width * (double) a.height + b.width * (double) b.height - intersectArea;
        return unionArea > 0 ? intersectArea / unionArea : 0.0;
    }

    /**
     * 遍历候选区域，从灰度图裁剪后逐块解码，结果（坐标已还原到原图）累积到 results。
     *
     * @param gray    灰度原图（裁剪源，由调用方释放）
     * @param regions 候选区域列表（原图坐标系）
     * @param results 累积结果列表（原地新增）
     */
    private void decodeRegions(Mat gray, List<Rect> regions, List<BarcodeResult> results) {
        for (Rect region : regions) {
            // 从灰度图裁剪候选区（ROI 共享数据，用完释放视图不影响原图）
            Mat crop = new Mat(gray, region);
            try {
                decodeCrop(crop, region, results);
            } finally {
                crop.release();
            }
        }
    }

    /**
     * 单个候选区域解码：优先单码 {@code decode}（一图一码假设，快且不漏），失败再 {@code decodeMultiple} 兜底。
     * <p>粘连多码（切割未完全分离）时单码 decode 只取首个，剩余由 decodeMultiple 捕获；
     * 切割遗漏的码则由阶段二整图兜底补齐。
     *
     * @param crop    裁剪出的小图 Mat（灰度，由调用方释放）
     * @param region  该区域在原图中的 Rect（用于坐标偏移与结果 rect）
     * @param results 累积结果列表（原地新增）
     */
    private void decodeCrop(Mat crop, Rect region, List<BarcodeResult> results) {
        // 优先单码 decode：一图一码假设下速度快
        tryDecodeSingle(crop, "morphcrop", region, results);
        // 继续 decodeMultiple 兜底：形态学闭运算可能把多个码连成一个块，
        // 单码只取首个会漏掉同块内其他码（如 QR+DM 粘连），用多码兜底补齐（去重过滤重复）
        tryDecode(crop, "morphcrop-multi", region, results);
    }

    /**
     * 单码解码：对裁剪小图做 0°/90°/180°/270° 旋转尝试，命中首个即返回。
     * <p>坐标反旋转回 0° 后叠加区域偏移还原到原图坐标系；{@code rect} 用角点包围盒（即使块粘连也保证各码位置独立）。
     *
     * @param mat      裁剪小图 Mat（灰度）
     * @param strategy 策略名（用于结果溯源）
     * @param bounds   该区域在原图中的 Rect（非 null，用于坐标偏移）
     * @param results  累积结果列表（原地新增）
     * @return 命中返回 true，全角度未识别返回 false
     */
    private boolean tryDecodeSingle(Mat mat, String strategy, Rect bounds, List<BarcodeResult> results) {
        BufferedImage image = matToBufferedImage(mat);
        if (image == null) {
            return false;
        }
        int offsetX = bounds.x;
        int offsetY = bounds.y;
        for (int angle = 0; angle < 360; angle += 90) {
            BufferedImage rotated = rotateImage(image, angle);
            try {
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(rotated)));
                // reset 防止上一次 decode 的内部状态影响本次（MultiFormatReader 非线程安全，已由实例锁串行）
                singleReader.reset();
                Result r = singleReader.decode(bitmap, hints);
                // 将旋转后图像的坐标统一反旋转回小图 0° 坐标系，再叠加区域偏移到原图坐标系
                ResultPoint[] localPoints = unrotatePoints(r.getResultPoints(),
                        rotated.getWidth(), rotated.getHeight(), angle);
                ResultPoint[] origPoints = offsetPoints(localPoints, offsetX, offsetY);
                if (!isDuplicate(r.getText(), r.getBarcodeFormat().toString(), origPoints, results)) {
                    results.add(new BarcodeResult(
                            r.getText(),
                            r.getBarcodeFormat().toString(),
                            origPoints,
                            pointsToRect(origPoints),
                            strategy,
                            angle
                    ));
                }
                return true; // 单码命中即返回（继续由 decodeMultiple 补齐同块其他码）
            } catch (NotFoundException ignored) {
                // 该角度未识别到单码，继续尝试下一角度
            }
        }
        return false;
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
     * 对给定图像尝试 0°/90°/180°/270° 多码识别，新增结果到 results（含跨角度去重）。
     * <p>{@code bounds} 非 null 时为切割路径（坐标叠加区域偏移）；为 null 时为整图兜底路径（无偏移）。
     * 每个码的 {@code rect} 统一用角点包围盒，即使形态学粘连成一个大块也能保证各码位置独立。
     *
     * @param mat      待解码的 Mat（灰度或预处理产物）
     * @param strategy 策略名（用于结果溯源）
     * @param bounds   区域 Rect（切割路径）或 null（整图兜底路径）
     * @param results  累积结果列表（原地新增）
     */
    private void tryDecode(Mat mat, String strategy, Rect bounds, List<BarcodeResult> results) {
        BufferedImage image = matToBufferedImage(mat);
        if (image == null) {
            return;
        }
        int offsetX = bounds != null ? bounds.x : 0;
        int offsetY = bounds != null ? bounds.y : 0;
        // 一维码对旋转敏感，尝试 0°、90°、180°、270°
        for (int angle = 0; angle < 360; angle += 90) {
            BufferedImage rotated = rotateImage(image, angle);
            try {
                Result[] found = decodeBarcodes(rotated);
                for (Result r : found) {
                    // 将旋转后图像的坐标统一反旋转回 0° 坐标系，再叠加区域偏移到原图坐标系
                    ResultPoint[] localPoints = unrotatePoints(
                            r.getResultPoints(), rotated.getWidth(), rotated.getHeight(), angle);
                    ResultPoint[] origPoints = offsetPoints(localPoints, offsetX, offsetY);
                    if (!isDuplicate(r.getText(), r.getBarcodeFormat().toString(),
                            origPoints, results)) {
                        // 用角点包围盒作为每个码的精确 rect；即使形态学粘连成一个大块，
                        // 各码的 ResultPoint 仍给出独立位置，保证排序精度
                        results.add(new BarcodeResult(
                                r.getText(),
                                r.getBarcodeFormat().toString(),
                                origPoints,
                                pointsToRect(origPoints),
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
     * @param points   新结果的角点（原图坐标系）
     * @param existing 已有结果列表
     * @return 重复返回 true，否则 false
     */
    private static boolean isDuplicate(String text, String type, ResultPoint[] points, List<BarcodeResult> existing) {
        for (BarcodeResult old : existing) {
            // 码制和内容都必须相同才有可能是重复
            if (!old.type().equals(type) || !old.data().equals(text)) {
                continue;
            }
            // 计算两个结果在原图坐标系下的中心点距离
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
     * 计算两组点在原图坐标系下的中心点距离。
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
     * 将旋转后图像上的坐标反旋转回 0° 坐标系。
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
     * @return 0° 坐标系下的点数组
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
     * 计算每组 ResultPoint 包围盒的对角线长度。
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
     * 将局部坐标点叠加区域偏移，还原到原图坐标系。偏移为 0 时直接返回原数组（零拷贝）。
     *
     * @param points  局部坐标点（小图 0° 坐标系）
     * @param offsetX 区域 X 偏移
     * @param offsetY 区域 Y 偏移
     * @return 原图坐标系下的点数组
     */
    private static ResultPoint[] offsetPoints(ResultPoint[] points, int offsetX, int offsetY) {
        if (offsetX == 0 && offsetY == 0) {
            return points;
        }
        ResultPoint[] result = new ResultPoint[points.length];
        for (int i = 0; i < points.length; i++) {
            result[i] = new ResultPoint(points[i].getX() + offsetX, points[i].getY() + offsetY);
        }
        return result;
    }

    /**
     * 由 ResultPoint 角点计算外接 Rect（用于整图兜底路径无切割框时的 rect）。
     *
     * @param points 角点数组（原图坐标系）
     * @return 外接 Rect；空数组返回零尺寸 Rect
     */
    private static Rect pointsToRect(ResultPoint[] points) {
        if (points == null || points.length == 0) {
            return new Rect(0, 0, 0, 0);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (ResultPoint p : points) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
        }
        int x = Math.max(0, (int) minX);
        int y = Math.max(0, (int) minY);
        // 宽高至少 1，避免零尺寸 Rect 影响后续排序容差计算
        int w = Math.max(1, (int) (maxX - minX));
        int h = Math.max(1, (int) (maxY - minY));
        return new Rect(x, y, w, h);
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
     * 按从上到下、从左到右对结果分行排序：同一行（Y 容差内）按 X 排序，不同行按 Y 排序。
     * <p>Y 容差取两者高度一半与 {@value #MIN_ROW_TOLERANCE} 的较大值，避免微小高度差或极小码导致行错乱。
     *
     * @param results 待排序结果列表（不修改原列表）
     * @return 排序后的新列表
     */
    public static List<BarcodeResult> sortByRow(List<BarcodeResult> results) {
        List<BarcodeResult> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> {
            int yDiff = a.rect.y - b.rect.y;
            // 同一行判定：Y 差小于容差则按 X 排，否则按 Y 排
            int tolerance = Math.max(Math.max(a.rect.height, b.rect.height) / 2, MIN_ROW_TOLERANCE);
            if (Math.abs(yDiff) < tolerance) {
                return a.rect.x - b.rect.x;
            }
            return yDiff;
        });
        return sorted;
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
     * @param points   条码角点坐标（已统一回原图坐标系）
     * @param rect     条码外接矩形（切割路径为切割框，整图兜底路径为角点包围盒；原图坐标系）
     * @param strategy 命中所用的预处理策略名
     * @param angle    命中时的旋转角度（0/90/180/270）
     */
    public record BarcodeResult(String data, String type, ResultPoint[] points, Rect rect,
                                String strategy, int angle) {
        public BarcodeResult {
            // 防御性拷贝：points 来自解码中间结果，拷贝后与外部隔离（ResultPoint 不可变，浅拷贝即安全）
            points = points == null ? new ResultPoint[0] : points.clone();
            rect = rect == null ? new Rect(0, 0, 0, 0) : rect;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (strategy=%s, angle=%d°, rect=[%d,%d %dx%d])",
                    type, data, strategy, angle, rect.x, rect.y, rect.width, rect.height);
        }
    }

    public static void main(String[] args) {
        // 从 classpath 加载示例图片
        /*URL url = Thread.currentThread().getContextClassLoader().getResource("images/2026-05-05_163050.png");
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
        }*/
        // F:\workspace\workspace-a\盘料图片\20260714185759_158_160.jpg
        // F:\workspace\workspace-a\盘料图片\2026-07-15_111031.png
        // F:\workspace\workspace-a\盘料图片\2026-07-15_093400_979.png
        // F:\workspace\workspace-a\盘料图片\20260714184754_156_160.jpg
        File file = new File("F:\\workspace\\workspace-a\\盘料图片\\2026-07-15_093400_979.png");
        try (OpenCVZxingQrReader scanner = new OpenCVZxingQrReader()) {
            List<BarcodeResult> results = scanner.scanOrdered(file);
            System.out.println("共识别到 " + results.size() + " 个唯一条码（已按从上到下、从左到右排序）：");
            results.forEach(System.out::println);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
