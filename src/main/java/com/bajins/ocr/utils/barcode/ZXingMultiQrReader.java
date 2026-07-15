package com.bajins.ocr.utils.barcode;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * 基于 ZXing(com.google.zxing) 的多条码识别工具类，支持一维码与二维码同图多码识别。
 *
 * <p>核心设计：
 * <ul>
 *   <li>静态工具类：ZXing 的 MultiFormatReader/GenericMultipleBarcodeReader 每次扫描即建即释，
 *       无持久句柄与模型加载，采用无状态静态方法，天然线程安全，对齐同目录 BoofCvMultiQrReader 风格。</li>
 *   <li>结果封装：ZXing Result 虽为纯 Java 对象不依赖 native，但仍拷贝到 {@link Barcode} record，
 *       统一 API、屏蔽 ZXing 类型细节，与同包 ZBarMultiQrReader 保持一致。</li>
 *   <li>多码识别：逐格式独立扫描--对每个启用格式单独跑一次 GenericMultipleBarcodeReader(完整原图)，使递归切分
 *       只围绕该格式展开，避免全格式混合时先找到的码(通常 QR)按其边界切分破坏 DATA_MATRIX 的 L 形定位角致漏识别；
 *       跨格式天然不重复(ZXing 不会将一码误识为另一格式)；不使用 ByQuadrantReader(其十字切四象限会切断横向一维码)。</li>
 *   <li>配置隔离：{@link Options} 持有启用的格式集合(BarcodeFormat 枚举，不可变)、TRY_HARDER 开关与
 *       放大倍数，提供 defaultConfig/qrOnly/linearOnly 预设与 of/plus/tryHarder/scale 自定义能力。</li>
 *   <li>输入预处理：转 8 位灰度后交 ZXing 内部 HybridBinarizer 二值化；可选 scale 放大(像素量按 scale² 增长)。</li>
 * </ul>
 *
 * <p>异常体系：
 * <ul>
 *   <li>参数非法(空图像/空配置/不存在文件/scale<1)抛 IllegalArgumentException。</li>
 *   <li>图像读取失败抛 IllegalStateException(包装受检 IOException)。</li>
 *   <li>未识别到任何条码(NotFoundException)视为正常情况，返回空列表，不抛异常。</li>
 * </ul>
 *
 * <p>ZBar 不支持：
 * <p>Data Matrix 工业、电子元件常用。
 * <p>Aztec 常见于各种交通票务（如火车票、登机牌）。
 * <p>MaxiCode 核心是中间有个类似“牛眼”的同心圆，主要由 UPS 快递使用。
 *
 * <p>依赖：com.google.zxing:core + javase
 * <p>参考：https://github.com/zxing/zxing
 */
public class ZXingMultiQrReader {

    /**
     * 默认启用的条码格式：常用一维码 + 二维码
     */
    private static final Set<BarcodeFormat> DEFAULT_FORMATS = Set.of(
            BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.ITF,
            BarcodeFormat.CODABAR, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E);

    /**
     * 默认配置单例(不可变，安全共享)
     */
    private static final Options DEFAULT_OPTIONS = new Options(DEFAULT_FORMATS, true, 1);

    /**
     * 识别图像中的所有条码(默认配置：常用一维码 + 二维码，TRY_HARDER 开启)。
     *
     * @param image 源图像，可为彩色或灰度，内部统一转灰度
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException image 为 null
     */
    public static List<Barcode> detect(BufferedImage image) {
        return detect(image, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图像中的所有条码。
     *
     * @param image   源图像，可为彩色或灰度
     * @param options 识别配置，决定启用哪些格式、是否 TRY_HARDER、放大倍数
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException image 或 options 为 null
     */
    public static List<Barcode> detect(BufferedImage image, Options options) {
        Objects.requireNonNull(image, "待识别图像不能为空");
        Objects.requireNonNull(options, "识别配置不能为空");
        return scan(image, options);
    }

    /**
     * 识别图片文件中的所有条码(默认配置)。
     *
     * @param file 图片文件，须存在且可读
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<Barcode> detect(File file) {
        return detect(file, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图片文件中的所有条码。
     *
     * @param file    图片文件，须存在且可读
     * @param options 识别配置
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException file 为 null 或不存在，或 options 为 null
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<Barcode> detect(File file, Options options) {
        return detect(readImage(file), options);
    }

    /**
     * 识别图像中所有条码的文本内容(默认配置)。
     *
     * @param image 源图像
     * @return 条码文本列表，顺序与 {@link #detect(BufferedImage)} 一致
     * @throws IllegalArgumentException image 为 null
     */
    public static List<String> decode(BufferedImage image) {
        return decode(image, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图像中所有条码的文本内容。
     *
     * @param image   源图像
     * @param options 识别配置
     * @return 条码文本列表，顺序与 {@link #detect(BufferedImage, Options)} 一致
     * @throws IllegalArgumentException image 或 options 为 null
     */
    public static List<String> decode(BufferedImage image, Options options) {
        List<String> messages = new ArrayList<>();
        for (Barcode barcode : detect(image, options)) {
            messages.add(barcode.text());
        }
        return messages;
    }

    /**
     * 识别图片文件中所有条码的文本内容(默认配置)。
     *
     * @param file 图片文件
     * @return 条码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<String> decode(File file) {
        return decode(file, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图片文件中所有条码的文本内容。
     *
     * @param file    图片文件
     * @param options 识别配置
     * @return 条码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在，或 options 为 null
     * @throws IllegalStateException    读取失败或格式不支持
     */
    public static List<String> decode(File file, Options options) {
        return decode(readImage(file), options);
    }

    /**
     * 执行一次完整扫描：放大 -> 转灰度 -> 二值化 -> 逐格式多码解码 -> 合并结果。
     *
     * <p>逐格式独立扫描：对每个启用格式单独跑一次 {@link GenericMultipleBarcodeReader}(完整原图)，
     * 使递归切分只围绕该格式展开。全格式混合时先找到的码(通常 QR)会按其边界切分图像，易破坏
     * DATA_MATRIX 的 L 形定位角导致漏识别；拆分后每种格式在完整原图独立检测，彻底规避此问题。
     * 跨格式天然不重复(ZXing 不会将一码误识为另一格式)，同格式内 multi reader 自身去重，故合并无需额外去重。
     *
     * @param image   源图像，已非空校验
     * @param options 识别配置，已非空校验
     * @return 识别到的条码列表(可能为空，但不为 null)
     */
    private static List<Barcode> scan(BufferedImage image, Options options) {
        // 可选放大：图过于密集时调大 scale，但像素量按 scale² 增长、耗时显著上升
        BufferedImage scaled = scaleImage(image, options.scale());
        // 转单通道灰度(ZXing 内部再二值化，这里统一通道格式)
        BufferedImage gray = toGray(scaled);

        // 完整原图位图复用：GenericMultipleBarcodeReader 递归切分时对子区域 crop 生成新位图，不改原图，可安全多次 decode
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(gray)));

        // 逐格式独立 multi 扫描后合并；图中无该格式时快速 NotFoundException 返回，实际开销可控
        List<Barcode> barcodes = new ArrayList<>();
        for (BarcodeFormat format : options.formats()) {
            List<Result> results = decodeMultipleForFormat(bitmap, format, options.tryHarder());
            // DATA_MATRIX 整图检测常因 WhiteRectangleDetector 受复杂背景干扰而失败，
            // 整图未命中时降级为滑动窗口网格扫描(每子图单独检测，等价手工剪切)，避免漏识别
            if (format == BarcodeFormat.DATA_MATRIX && results.isEmpty()) {
                results = gridScanDataMatrix(gray, options.tryHarder());
            }
            for (Result result : results) {
                // Result 为纯 Java 对象，拷贝到 record 统一 API、屏蔽 ZXing 类型
                barcodes.add(toBarcode(result));
            }
        }
        return barcodes;
    }

    /**
     * 对单个格式在完整原图上做多码递归扫描。
     *
     * <p>POSSIBLE_FORMATS 仅含该格式，使 {@link GenericMultipleBarcodeReader} 的递归切分围绕该格式展开，
     * 避免多格式混合时先找到的码(通常 QR)按其边界切分、破坏其他格式(如 DATA_MATRIX 的 L 形定位角)。
     *
     * @param bitmap    完整原图已二值化位图，可被多次 decode 复用
     * @param format    本次唯一启用的条码格式
     * @param tryHarder 是否开启 TRY_HARDER(提升识别率，略增耗时)
     * @return 该格式识别到的结果列表(可能为空，但不为 null)
     */
    private static List<Result> decodeMultipleForFormat(BinaryBitmap bitmap, BarcodeFormat format, boolean tryHarder) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        if (tryHarder) {
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        }
        // 限定单一格式，既避免 MultiFormatReader 盲试全部格式拖慢，又保证递归切分围绕该格式
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(format));
        try {
            // MultiFormatReader 无状态，每次独立创建；GenericMultipleBarcodeReader 按已找到码边界递归切分
            MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(new MultiFormatReader());
            Result[] results = multiReader.decodeMultiple(bitmap, hints);
            return Arrays.asList(results);
        } catch (NotFoundException e) {
            // 图中无该格式条码属正常情况
            return List.of();
        }
    }

    /**
     * 将 ZXing {@link Result} 拷贝为不可变 {@link Barcode}，屏蔽 ZXing 类型细节、统一 API。
     *
     * @param result ZXing 识别结果，非 null
     * @return 拷贝后的条码记录
     */
    private static Barcode toBarcode(Result result) {
        ResultPoint[] points = result.getResultPoints();
        return new Barcode(
                result.getText(),
                result.getBarcodeFormat(),
                categorizeBarcodeType(result.getBarcodeFormat()),
                points == null ? List.of() : List.of(points));
    }

    /** 网格扫描子图边长：过大趋近整图(仍受背景干扰)，过小易切断 DM；700 经实测命中 */
    private static final int GRID_TILE = 700;
    /** 网格扫描步长：tile/2 即 50% 重叠，保证 DM 至少被一个完整子图覆盖 */
    private static final int GRID_STEP = GRID_TILE / 2;

    /**
     * DATA_MATRIX 滑动窗口网格扫描：将整图切成重叠子图，每子图单独 multi 解码。
     *
     * <p>整图场景下 ZXing {@code DataMatrixReader} 的 {@code WhiteRectangleDetector} 常因复杂背景干扰
     * 定位不到 DM 白边致漏识别；切到仅含 DM 的子图(背景单纯)即可识别，等价于手工剪切。
     * 跨子图可能重复命中同一 DM，按文本去重；位置点经 {@link #translateResult} 修正回全图坐标。
     *
     * @param gray      完整灰度图(已放大)，网格在其上切分
     * @param tryHarder 是否开启 TRY_HARDER
     * @return 去重后的 DM 结果列表(可能为空，但不为 null)
     */
    private static List<Result> gridScanDataMatrix(BufferedImage gray, boolean tryHarder) {
        int width = gray.getWidth();
        int height = gray.getHeight();
        // 图不大于一个 tile 时网格退化为整图(整图已失败)，无意义，直接返回空避免无谓扫描
        if (width <= GRID_TILE && height <= GRID_TILE) {
            return List.of();
        }
        Set<String> seenTexts = new HashSet<>();
        List<Result> results = new ArrayList<>();
        for (int y = 0; y < height; y += GRID_STEP) {
            for (int x = 0; x < width; x += GRID_STEP) {
                int w = Math.min(GRID_TILE, width - x);
                int h = Math.min(GRID_TILE, height - y);
                // 子图过小(图边缘残余)不足以容纳 DM，跳过
                if (w < 100 || h < 100) {
                    continue;
                }
                BufferedImage sub = gray.getSubimage(x, y, w, h);
                BinaryBitmap subBitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(sub)));
                for (Result result : decodeMultipleForFormat(subBitmap, BarcodeFormat.DATA_MATRIX, tryHarder)) {
                    // 跨子图可能重复命中同一 DM，按文本去重
                    if (seenTexts.add(result.getText())) {
                        results.add(translateResult(result, x, y));
                    }
                }
            }
        }
        return results;
    }

    /**
     * 将子图内识别结果的定位点坐标平移回全图坐标。
     *
     * @param result  子图内识别结果，非 null
     * @param offsetX 子图在整图中的 x 偏移
     * @param offsetY 子图在整图中的 y 偏移
     * @return 定位点已平移的新 Result(文本/格式不变)
     */
    private static Result translateResult(Result result, int offsetX, int offsetY) {
        ResultPoint[] points = result.getResultPoints();
        if (points != null) {
            ResultPoint[] shifted = new ResultPoint[points.length];
            for (int i = 0; i < points.length; i++) {
                shifted[i] = new ResultPoint(points[i].getX() + offsetX, points[i].getY() + offsetY);
            }
            points = shifted;
        }
        return new Result(result.getText(), result.getRawBytes(), points, result.getBarcodeFormat());
    }

    /**
     * 放大图像(scale>1 时)，双三次插值保持清晰度。
     *
     * @param img   原始图像
     * @param scale 放大倍数，<=1 时原样返回
     * @return 放大后的图像(或原图)
     */
    private static BufferedImage scaleImage(BufferedImage img, int scale) {
        if (scale <= 1) {
            return img;
        }
        BufferedImage scaled = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(img, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        graphics.dispose();
        return scaled;
    }

    /**
     * 转为 8 位灰度图(已是灰度图则原样返回)。
     *
     * @param img 原始图像，可为任意颜色类型
     * @return 8 位灰度图
     */
    private static BufferedImage toGray(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return img;
        }
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        graphics.drawImage(img, 0, 0, null);
        graphics.dispose();
        return gray;
    }

    /**
     * 读取图片文件为 BufferedImage。
     *
     * @param file 图片文件，须存在且可读
     * @return 解码后的图像
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或格式不支持(包装受检 IOException)
     */
    private static BufferedImage readImage(File file) {
        Objects.requireNonNull(file, "图片文件不能为空");
        if (!file.isFile()) {
            throw new IllegalArgumentException("图片文件不存在或不是文件: " + file);
        }
        try {
            BufferedImage image = ImageIO.read(file);
            // ImageIO.read 对不支持格式返回 null，需显式判空
            if (image == null) {
                throw new IllegalStateException("无法解码图片(格式不支持或文件损坏): " + file);
            }
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("读取图片失败: " + file, e);
        }
    }

    /**
     * 按 ZXing BarcodeFormat 枚举归类为一维码 / 二维码 / 其它。
     *
     * @param format ZXing 条码格式枚举，非 null
     * @return 分类:二维码 / 一维码 / 其它
     */
    private static String categorizeBarcodeType(BarcodeFormat format) {
        return switch (format) {
            // 二维码
            case QR_CODE, PDF_417, DATA_MATRIX, AZTEC, MAXICODE -> "二维码";
            // 一维码
            case CODE_128, CODE_39, CODE_93, CODABAR, EAN_13, EAN_8, ITF, UPC_A, UPC_E, RSS_14, RSS_EXPANDED,
                 UPC_EAN_EXTENSION -> "一维码";
            default -> "其它";
        };
    }

    /**
     * 单个条码识别结果(不可变)。
     *
     * @param text         条码内容文本
     * @param format       ZXing 条码格式枚举(如 QR_CODE、EAN_13)
     * @param category     按格式归类:二维码 / 一维码 / 其它
     * @param resultPoints 定位点列表(可能为空)，每点含 getX()/getY() 坐标
     */
    public record Barcode(String text, BarcodeFormat format, String category, List<ResultPoint> resultPoints) {
    }

    /**
     * 识别配置(不可变)，持有启用的格式集合、TRY_HARDER 开关与放大倍数。
     */
    public static final class Options {

        private final Set<BarcodeFormat> formats;
        private final boolean tryHarder;
        private final int scale;

        private Options(Set<BarcodeFormat> formats, boolean tryHarder, int scale) {
            this.formats = Set.copyOf(formats);
            this.tryHarder = tryHarder;
            this.scale = scale;
        }

        /**
         * 默认配置：启用常用一维码 + 二维码，TRY_HARDER 开启，不放大。
         *
         * @return 默认配置
         */
        public static Options defaultConfig() {
            return DEFAULT_OPTIONS;
        }

        /**
         * 仅二维码配置：QR_CODE + PDF_417。
         *
         * @return 仅二维码配置
         */
        public static Options qrOnly() {
            return new Options(Set.of(BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX), true, 1);
        }

        /**
         * 仅一维码配置：CODE/UPC/EAN/ITF/CODABAR。
         *
         * @return 仅一维码配置
         */
        public static Options linearOnly() {
            return new Options(Set.of(
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.ITF,
                    BarcodeFormat.CODABAR, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E), true, 1);
        }

        /**
         * 自定义启用格式集合(TRY_HARDER 默认开启，不放大)。
         *
         * @param formats 需启用的条码格式，至少一个；重复会被去重，null 元素抛 NullPointerException
         * @return 新配置
         * @throws IllegalArgumentException formats 为 null 或空
         */
        public static Options of(BarcodeFormat... formats) {
            if (formats == null || formats.length == 0) {
                throw new IllegalArgumentException("启用的条码格式不能为空");
            }
            // EnumSet 容忍重复传入，构造时再 Set.copyOf 转不可变
            EnumSet<BarcodeFormat> set = EnumSet.noneOf(BarcodeFormat.class);
            for (BarcodeFormat format : formats) {
                set.add(Objects.requireNonNull(format, "条码格式不能为 null"));
            }
            return new Options(set, true, 1);
        }

        /**
         * 在当前配置基础上追加启用格式，返回新配置(原配置不变)。
         *
         * @param formats 追加的条码格式；为空则返回原配置
         * @return 含追加格式的新配置
         */
        public Options plus(BarcodeFormat... formats) {
            if (formats == null || formats.length == 0) {
                return this;
            }
            EnumSet<BarcodeFormat> merged = EnumSet.copyOf(this.formats);
            for (BarcodeFormat format : formats) {
                merged.add(Objects.requireNonNull(format, "条码格式不能为 null"));
            }
            return new Options(merged, this.tryHarder, this.scale);
        }

        /**
         * 设置 TRY_HARDER 开关，返回新配置(原配置不变)。
         *
         * @param enabled 是否开启 TRY_HARDER(提升识别率，略增耗时)
         * @return 新配置
         */
        public Options tryHarder(boolean enabled) {
            return new Options(this.formats, enabled, this.scale);
        }

        /**
         * 设置放大倍数，返回新配置(原配置不变)。
         *
         * @param scale 放大倍数，须 >= 1；图过于密集时可调大，但像素量按 scale² 增长
         * @return 新配置
         * @throws IllegalArgumentException scale < 1
         */
        public Options scale(int scale) {
            if (scale < 1) {
                throw new IllegalArgumentException("放大倍数必须 >= 1: " + scale);
            }
            return new Options(this.formats, this.tryHarder, scale);
        }

        /**
         * @return 启用的条码格式集合(不可变)
         */
        public Set<BarcodeFormat> formats() {
            return formats;
        }

        /**
         * @return 是否开启 TRY_HARDER
         */
        public boolean tryHarder() {
            return tryHarder;
        }

        /**
         * @return 放大倍数
         */
        public int scale() {
            return scale;
        }
    }

    public static void main(String[] args) throws Exception {
        // 从 classpath 读取示例图片(可改为任意 File 路径)
        /*URL url = Thread.currentThread().getContextClassLoader().getResource("images/2026-05-05_163050.png");
        if (url == null) {
            throw new IllegalArgumentException("未找到示例图片资源 images/2026-05-05_163050.png");
        }
        File file = new File(url.toURI());*/
        // F:\workspace\workspace-a\盘料图片\20260714185759_158_160.jpg
        // F:\workspace\workspace-a\盘料图片\2026-07-15_111031.png
        // F:\workspace\workspace-a\盘料图片\2026-07-15_093400_979.png
        // F:\workspace\workspace-a\盘料图片\20260714184754_156_160.jpg
        File file = new File("F:\\workspace\\workspace-a\\盘料图片\\20260714184754_156_160.jpg");

        List<Barcode> results = detect(file);
        System.out.println("共识别到 " + results.size() + " 个条码：");
        for (Barcode barcode : results) {
            System.out.println("----------------------");
            System.out.println("格式   : " + barcode.format());
            System.out.println("分类   : " + barcode.category());
            System.out.println("内容   : " + barcode.text());
            System.out.println("位置点 : " + barcode.resultPoints());
        }
    }
}
