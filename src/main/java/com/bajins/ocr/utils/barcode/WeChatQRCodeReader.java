package com.bajins.ocr.utils.barcode;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.StringVector;
import org.bytedeco.opencv.opencv_wechat_qrcode.WeChatQRCode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 OpenCV WeChatQRCode 的多二维码识别器（bytedeco javacpp 风格）。
 *
 * <p>背景：WeChatQRCode 属 OpenCV contrib（wechat_qrcode 模块）。项目现有 openpnp opencv native 仅含主模块、
 * 不含 wechat_qrcode；而 bytedeco 的官方绑定风格类（org.opencv.wechat_qrcode.*）缺乏标准 JNI 库
 * （opencv_java490.dll）无法链接。故本类采用 bytedeco javacpp 风格（org.bytedeco.opencv.*）：native 由
 * bytedeco 的 jniopencv_wechat_qrcode.dll + opencv_wechat_qrcode490.dll 提供，包名与 openpnp 的
 * org.opencv.* 隔离、native 库不同名，可与同包 OpenCvQrCodeDetector（openpnp）安全共存于同一 JVM。
 *
 * <p>核心设计：
 * <ul>
 *   <li>实例类 AutoCloseable：WeChatQRCode 构造需加载 4 个 caffe 模型（百毫秒级开销），故复用实例而非每次创建；
 *       构造时加载模型，close() 释放 native 句柄与提取的临时模型文件，调用方用 try-with-resources。</li>
 *   <li>模型来源：默认从 classpath（wechat_qrcode/）提取 4 个模型到临时文件（detect/sr 各 prototxt+caffemodel）；
 *       亦支持指定模型目录（{@link #WeChatQRCodeReader(File)}）。WeChatQRCode 接收文件路径、不支持流，
 *       故 classpath 资源须先落盘。</li>
 *   <li>多码识别：detectAndDecode 一次调用解码图中所有 QR，返回 StringVector（解码文本）+ MatVector（每码一组
 *       4 角点）；解码失败的空串予以过滤。</li>
 *   <li>输入支持 BufferedImage/File/Mat（bytedeco）三种重载；BufferedImage 经 Java2DFrameUtils.toMat 转换。</li>
 * </ul>
 *
 * <p>资源管理：所有 bytedeco native 对象（Mat/StringVector/MatVector/WeChatQRCode）均 AutoCloseable，
 *   scan 在 finally 释放 results 与 points（MatVector.close 同时释放其内部 Mat）；detect(BufferedImage/File)
 *   创建的 Mat 在 finally 释放，detect(Mat) 传入的 Mat 由调用方拥有。角点经 FloatIndexer 线性读取后不持有 native 引用。
 *
 * <p>线程安全：WeChatQRCode native 引擎非可重入，{@link #scan} 以实例锁串行化；
 *   高并发请为每个线程创建独立实例，而非放开锁（对齐 rust-paddle-ocr 规范）。
 *
 * <p>异常体系：
 * <ul>
 *   <li>参数非法（空图像/空 Mat/不存在文件/空模型目录）抛 IllegalArgumentException。</li>
 *   <li>模型资源缺失或图片读取失败抛 IOException。</li>
 *   <li>未识别到任何二维码（结果集为空）视为正常，返回空列表，不抛异常。</li>
 *   <li>bytedeco native 库加载失败抛 ExceptionInInitializerError 或 UnsatisfiedLinkError（系统级，不封装）。</li>
 * </ul>
 *
 * <p>局限：仅识别 QR 码；依赖 4 个模型文件；bytedeco native 体积较大。
 *
 * <p>依赖：org.bytedeco:opencv（含 contrib）、org.bytedeco:javacpp、org.bytedeco:javacv（Java2DFrameUtils）
 * <p>模型来源：https://github.com/WeChatCV/opencv_3rdparty/tree/wechat_qrcode
 * <p>参考：https://docs.opencv.org/4.9.0/d5/d04/classcv_1_1wechat__qrcode_1_1WeChatQRCode.html
 */
public class WeChatQRCodeReader implements AutoCloseable {

    /**
     * classpath 模型资源目录前缀
     */
    private static final String MODEL_RESOURCE_DIR = "wechat_qrcode/";
    /**
     * 检测模型定义文件名
     */
    private static final String DET_PROTO = "detect.prototxt";
    /**
     * 检测模型权重文件名
     */
    private static final String DET_MODEL = "detect.caffemodel";
    /**
     * 超分辨率模型定义文件名
     */
    private static final String SR_PROTO = "sr.prototxt";
    /**
     * 超分辨率模型权重文件名
     */
    private static final String SR_MODEL = "sr.caffemodel";

    static {
        // 触发 bytedeco opencv native 库加载（core/imgcodecs）；wechat_qrcode native 在 WeChatQRCode
        // 构造时由 javacpp 按需加载其依赖链
        Loader.load(opencv_imgcodecs.class);
    }

    /**
     * native 检测器实例（持有模型，复用）
     */
    private final WeChatQRCode detector;
    /**
     * 从 classpath 提取的临时模型文件（close 时删除）；为 null 表示使用外部模型目录、无需清理
     */
    private final List<Path> extractedTempModels;

    /**
     * 默认构造：从 classpath（wechat_qrcode/）提取 4 个模型到临时文件并加载。
     *
     * @throws IOException 模型资源缺失或提取失败
     */
    public WeChatQRCodeReader() throws IOException {
        this(extractClasspathModels());
    }

    /**
     * 内部构造：用提取的临时模型文件构造检测器。
     *
     * @param models 提取的模型文件集合
     */
    private WeChatQRCodeReader(ModelFiles models) {
        this.detector = new WeChatQRCode(
                models.detProto().toString(), models.detModel().toString(),
                models.srProto().toString(), models.srModel().toString());
        this.extractedTempModels = models.tempPaths();
    }

    /**
     * 指定模型目录构造：从目录读取 4 个模型文件（不提取、不清理）。
     *
     * @param modelDir 模型目录，须含 detect.prototxt/detect.caffemodel/sr.prototxt/sr.caffemodel
     * @throws IllegalArgumentException modelDir 为 null 或不是目录
     */
    public WeChatQRCodeReader(File modelDir) {
        Objects.requireNonNull(modelDir, "模型目录不能为空");
        if (!modelDir.isDirectory()) {
            throw new IllegalArgumentException("模型目录不存在或不是目录: " + modelDir);
        }
        Path dir = modelDir.toPath();
        this.detector = new WeChatQRCode(
                dir.resolve(DET_PROTO).toString(), dir.resolve(DET_MODEL).toString(),
                dir.resolve(SR_PROTO).toString(), dir.resolve(SR_MODEL).toString());
        this.extractedTempModels = null;
    }

    /**
     * 识别图片文件中的所有二维码。
     *
     * @param file 图片文件，须存在且可读
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IOException              图片读取失败
     */
    public List<Barcode> detect(File file) throws IOException {
        Objects.requireNonNull(file, "图片文件不能为空");
        if (!file.isFile()) {
            throw new IllegalArgumentException("图片文件不存在或不是文件: " + file);
        }
        try (Mat img = readMat(file)) {
            return scan(img);
        }
    }

    /**
     * 读取图片文件为 bytedeco Mat（中文路径免疫）。
     *
     * <p>OpenCV {@code imread} 在 Windows 下经 C 运行时 {@code fopen}（ANSI 编码）读取文件路径，
     * 含非 ASCII 字符（如中文）的路径会因编码错配而打开失败。故本方法先用 Java NIO 读字节再
     * {@code imdecode} 解码，绕过 native {@code fopen}；{@code imdecode} 失败（OpenCV 不支持的格式）
     * 时用 {@code ImageIO} 兜底转 Mat，兼顾中文路径与格式兼容。
     *
     * @param file 图片文件，须存在且可读
     * @return 解码后的 Mat（由调用方 close）
     * @throws IOException 图片读取失败（格式不支持或文件损坏）
     */
    private Mat readMat(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        // A: imdecode 优先——绕过 native fopen，免疫中文路径，保留 OpenCV 全格式解码能力
        Mat img;
        try (BytePointer bp = new BytePointer(data);
             Mat buf = new Mat(1, data.length, opencv_core.CV_8UC1, bp)) {
            img = opencv_imgcodecs.imdecode(buf, opencv_imgcodecs.IMREAD_COLOR);
        }
        if (img == null || img.empty()) {
            if (img != null) {
                img.close();
            }
            // B: ImageIO 兜底——OpenCV imdecode 不支持的格式（如部分 CMYK JPEG），由 ImageIO 解码后转 Mat
            BufferedImage bi = ImageIO.read(file);
            if (bi == null) {
                throw new IOException("无法读取图片(格式不支持或文件损坏): " + file);
            }
            return Java2DFrameUtils.toMat(bi);
        }
        return img;
    }

    /**
     * 识别图像中的所有二维码。
     *
     * @param image 源图像，可为彩色或灰度
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException image 为 null
     */
    public List<Barcode> detect(BufferedImage image) {
        Objects.requireNonNull(image, "待识别图像不能为空");
        Mat img = Java2DFrameUtils.toMat(image);
        try {
            return scan(img);
        } finally {
            img.close();
        }
    }

    /**
     * 识别 bytedeco Mat 中的所有二维码。
     * <p>传入的 Mat 由调用方拥有，本方法不释放。
     *
     * @param img 源图像 Mat（不会被修改，由调用方负责释放）
     * @return 识别到的二维码列表（可能为空，但不为 null）
     * @throws IllegalArgumentException img 为 null 或为空 Mat
     */
    public List<Barcode> detect(Mat img) {
        Objects.requireNonNull(img, "待识别图像不能为空");
        if (img.empty()) {
            throw new IllegalArgumentException("待识别图像为空 Mat");
        }
        return scan(img);
    }

    /**
     * 识别图片文件中所有二维码的文本内容。
     *
     * @param file 图片文件
     * @return 二维码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IOException              图片读取失败
     */
    public List<String> decode(File file) throws IOException {
        return toTexts(detect(file));
    }

    /**
     * 识别图像中所有二维码的文本内容。
     *
     * @param image 源图像
     * @return 二维码文本列表
     * @throws IllegalArgumentException image 为 null
     */
    public List<String> decode(BufferedImage image) {
        return toTexts(detect(image));
    }

    /**
     * 识别 bytedeco Mat 中所有二维码的文本内容。
     *
     * @param img 源图像 Mat（由调用方负责释放）
     * @return 二维码文本列表
     * @throws IllegalArgumentException img 为 null 或为空 Mat
     */
    public List<String> decode(Mat img) {
        return toTexts(detect(img));
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
     * 执行一次识别：调用 detectAndDecode 解码所有 QR，角点经 FloatIndexer 线性读取。
     * <p>实例锁串行化，保护非可重入的 native 引擎。
     *
     * @param img 源图像 Mat（由调用方拥有，本方法不释放）
     * @return 识别到的二维码列表（可能为空，但不为 null）
     */
    private synchronized List<Barcode> scan(Mat img) {
        MatVector points = new MatVector();
        StringVector results = null;
        try {
            results = detector.detectAndDecode(img, points);
            List<Barcode> barcodes = new ArrayList<>();
            long n = results.size();
            for (long i = 0; i < n; i++) {
                String text = results.get(i).getString();
                // 跳过检测到但解码失败的码（空串）
                if (text == null || text.isEmpty()) {
                    continue;
                }
                barcodes.add(new Barcode(text, extractCorners(points.get(i))));
            }
            return barcodes;
        } finally {
            // StringVector/MatVector 释放容器及其内部 native 对象（MatVector.close 析构其内部 Mat）
            if (results != null) {
                results.close();
            }
            points.close();
        }
    }

    /**
     * 从角点 Mat 提取角点坐标。
     * <p>WeChatQRCode 每个角点 Mat 含 4 个角点，按 (x,y) 对线性排列；用 FloatIndexer 一维读取，
     * 兼容 (4,2) CV_32F 与 (4,1) CV_32FC2 等内存布局。
     *
     * @param corners 角点 Mat（由 MatVector 持有，本方法不释放）
     * @return 角点坐标列表；Mat 为空返回空列表
     */
    private static List<Corner> extractCorners(Mat corners) {
        if (corners == null || corners.empty()) {
            return List.of();
        }
        int n = (int) (corners.total() * corners.channels());
        if (n < 2) {
            return List.of();
        }
        // 直接从 native 数据地址读取 float，绕过 FloatIndexer 多维索引限制
        try (BytePointer bp = corners.data()) {
            float[] data = new float[n];
            new FloatPointer(bp).get(data);
            List<Corner> list = new ArrayList<>(n / 2);
            // 按连续 (x,y) 对解析，每对为一个角点
            for (int k = 0; k + 1 < n; k += 2) {
                list.add(new Corner(data[k], data[k + 1]));
            }
            return list;
        }
    }

    /**
     * 从 classpath 提取 4 个模型文件到临时目录。
     *
     * @return 提取的模型文件集合
     * @throws IOException 资源缺失或提取失败
     */
    private static ModelFiles extractClasspathModels() throws IOException {
        return new ModelFiles(
                extractResource(MODEL_RESOURCE_DIR + DET_PROTO),
                extractResource(MODEL_RESOURCE_DIR + DET_MODEL),
                extractResource(MODEL_RESOURCE_DIR + SR_PROTO),
                extractResource(MODEL_RESOURCE_DIR + SR_MODEL));
    }

    /**
     * 将 classpath 资源提取到临时文件。
     *
     * @param resourcePath classpath 资源路径
     * @return 临时文件路径
     * @throws IOException 资源缺失或写入失败
     */
    private static Path extractResource(String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("未找到模型资源: " + resourcePath
                        + "，请确认 classpath 含 wechat_qrcode/ 目录及其 4 个模型文件");
            }
            String name = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            Path tmp = Files.createTempFile("wechat-qrcode-", "-" + name);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    @Override
    public void close() {
        detector.close();
        // 仅清理由本实例从 classpath 提取的临时模型文件
        if (extractedTempModels != null) {
            for (Path p : extractedTempModels) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响关闭，JVM 退出时由系统清理
                }
            }
        }
    }

    /**
     * 单个二维码识别结果（不可变）。
     *
     * @param text    解码内容文本（非空）
     * @param corners 四个角点坐标（原图坐标系；可能为空）
     */
    public record Barcode(String text, List<Corner> corners) {
        @Override
        public String toString() {
            return String.format("[QR] %s (corners=%s)", text, corners);
        }
    }

    /**
     * 角点坐标（不可变）。
     *
     * @param x X 坐标
     * @param y Y 坐标
     */
    public record Corner(double x, double y) {
        @Override
        public String toString() {
            return String.format("(%.1f,%.1f)", x, y);
        }
    }

    /**
     * 模型文件集合（内部用）。
     *
     * @param detProto 检测模型定义文件
     * @param detModel 检测模型权重文件
     * @param srProto  超分辨率模型定义文件
     * @param srModel  超分辨率模型权重文件
     */
    private record ModelFiles(Path detProto, Path detModel, Path srProto, Path srModel) {
        /**
         * @return 全部模型文件路径（用于临时文件清理）
         */
        List<Path> tempPaths() {
            return List.of(detProto, detModel, srProto, srModel);
        }
    }

    /**
     * 测试入口：从 classpath 读取示例图片，加载默认模型并识别输出。
     *
     * @param args 未使用
     * @throws URISyntaxException 示例资源 URI 解析失败
     * @throws IOException        模型加载或图片读取失败
     */
    public static void main(String[] args) throws URISyntaxException, IOException {
        /*URL url = Thread.currentThread().getContextClassLoader().getResource("images/2026-05-05_163050.png");
        if (url == null) {
            throw new IllegalArgumentException("未找到示例图片资源 images/2026-05-05_163050.png");
        }
        File file = new File(url.toURI());*/
        // F:\workspace\workspace-a\盘料图片\20260714185759_158_160.jpg
        // F:\workspace\workspace-a\盘料图片\2026-07-15_111031.png
        // F:\workspace\workspace-a\盘料图片\2026-07-15_093400_979.png
        // F:\workspace\workspace-a\盘料图片\20260714184754_156_160.jpg
        File file = new File("F:\\workspace\\workspace-a\\盘料图片\\20260714185759_158_160.jpg");

        try (WeChatQRCodeReader reader = new WeChatQRCodeReader()) {
            List<Barcode> results = reader.detect(file);
            System.out.println("共识别到 " + results.size() + " 个二维码：");
            for (Barcode b : results) {
                System.out.println("----------------------");
                System.out.println("内容: " + b.text());
                System.out.println("角点: " + b.corners());
            }
        }
    }
}
