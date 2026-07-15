package com.bajins.ocr.utils.barcode;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;
import nu.pattern.OpenCV;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * OpenCV + ZXing 条码识别器：基于形态学的候选区域定位 + 分区解码 + 全局兜底的多码识别方案。
 * <p>
 * 识别流程：
 * <ol>
 *   <li><b>图像预处理</b>：灰度化 -> 高斯模糊去噪 -> 自适应阈值二值化，增强条码与背景对比度。</li>
 *   <li><b>候选区域定位</b>：用竖向形态学闭运算连接一维码的竖条特征，查找轮廓并按宽高/宽高比过滤，
 *       合并重叠区域后得到条码候选矩形。</li>
 *   <li><b>分区解码</b>：对每个候选区域裁剪后用 ZXing {@code MultiFormatReader} 解码，
 *       同时支持一维码（CODE_128/EAN 等）与二维码（QR/DATA_MATRIX 等）。</li>
 *   <li><b>全局兜底</b>：用 {@code GenericMultipleBarcodeReader} 对整图扫描，捕获分区定位遗漏的条码。</li>
 *   <li><b>合并去重</b>：按内容相同且位置接近判定为同一码，避免全局扫描与分区解码重复计数。</li>
 *   <li><b>排序</b>：按从上到下、从左到右排序输出，同一行（Y 容差内）按 X 排序。</li>
 * </ol>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>资源管理：所有 OpenCV {@code Mat} 中间产物在方法内显式 {@code release()}，防止 native 内存泄漏。</li>
 *   <li>静态加载：类初始化时加载 OpenCV 原生库，库缺失会抛 {@code ExceptionInInitializerError}。</li>
 *   <li>线程安全：本类无实例状态，方法均为静态；ZXing reader 在方法内局部创建，天然线程安全。</li>
 * </ul>
 */
public class OcvZxingQrReader {

    static {
        // 类初始化时加载对应平台的 OpenCV 原生库（无需系统安装），库缺失会抛 ExceptionInInitializerError
        OpenCV.loadLocally();
    }

    /**
     * 单个条码识别结果，包含内容、码制与在原图中的位置信息。
     */
    public static class BarcodeResult {
        /**
         * 条码解码内容文本
         */
        public String text;
        /**
         * 码制名称（如 QR_CODE、CODE_128）
         */
        public String format;
        /**
         * 条码包围盒左上角 X 坐标（原图坐标系，已叠加区域偏移）
         */
        public int x;
        /**
         * 条码包围盒左上角 Y 坐标（原图坐标系，已叠加区域偏移）
         */
        public int y;
        /**
         * 条码包围盒宽度
         */
        public int width;
        /**
         * 条码包围盒高度
         */
        public int height;

        /**
         * 构造条码识别结果。
         *
         * @param text   条码内容文本
         * @param format 码制名称
         * @param x      包围盒左上角 X（原图坐标系）
         * @param y      包围盒左上角 Y（原图坐标系）
         * @param width  包围盒宽度
         * @param height 包围盒高度
         */
        public BarcodeResult(String text, String format, int x, int y, int width, int height) {
            this.text = text;
            this.format = format;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (x=%d, y=%d)", format, text, x, y);
        }
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
     * 主识别方法：执行完整识别流程并按从上到下、从左到右排序返回结果。
     * <p>流程：读取图 -> 预处理 -> 候选区域定位 -> 分区解码 -> 全局兜底 -> 合并去重 -> 排序。
     *
     * @param imagePath 待识别图片的绝对路径
     * @return 去重并排序后的全部条码结果（可能为空，但不为 null）
     * @throws Exception 图片读取失败或解码过程中发生 I/O 错误时抛出
     */
    public static List<BarcodeResult> scanOrdered(String imagePath) throws Exception {
        Mat image = readMat(new File(imagePath));

        // 1. 图像预处理 - 增强条码对比度
        Mat gray = new Mat();
        // 转灰度：降低通道维度，便于后续阈值化处理
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

        // 高斯模糊去噪：抑制细小噪点对二值化的干扰
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

        // 自适应阈值二值化：按局部邻域计算阈值，抵抗光照不均
        Mat binary = new Mat();
        Imgproc.adaptiveThreshold(blurred, binary, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 11, 2);

        // 2. 查找所有可能的条码区域
        List<Rect> candidateRegions = findBarcodeRegions(binary);

        // 3. 逐个区域识别
        List<BarcodeResult> results = new ArrayList<>();
        // 用 ImageIO 再读一份 BufferedImage 供区域裁剪（Mat 与 BufferedImage 分离处理，职责清晰）
        BufferedImage fullImage = ImageIO.read(new File(imagePath));

        for (Rect rect : candidateRegions) {
            // 裁剪候选区域：getSubimage 共享原图数据，零拷贝
            BufferedImage region = fullImage.getSubimage(
                    rect.x, rect.y, rect.width, rect.height);

            // 尝试识别一维码和二维码，传入区域偏移以便结果坐标还原到原图坐标系
            BarcodeResult result = decodeBarcode(region, rect.x, rect.y);
            if (result != null) {
                results.add(result);
            }
        }

        // 4. 全局扫描兜底（用于捕获分区定位遗漏的条码）
        List<BarcodeResult> globalResults = scanGlobal(fullImage);
        // 合并并去重（根据位置和内容）
        results = mergeResults(results, globalResults);

        // 5. 按从上到下、从左到右排序
        results.sort((a, b) -> {
            int yDiff = a.y - b.y;
            // Y方向允许一定容差（同一行的条码）：容差取两者高度的一半，避免微小高度差导致行错乱
            if (Math.abs(yDiff) < Math.max(a.height, b.height) / 2) {
                return a.x - b.x; // 同一行则按X排序
            }
            return yDiff; // 不同行按Y排序
        });

        // 清理：释放所有 OpenCV Mat 中间产物，防止 native 内存泄漏
        image.release();
        gray.release();
        blurred.release();
        binary.release();

        return results;
    }

    /**
     * 在二值图上定位条码候选区域：形态学连接竖条特征 -> 查找轮廓 -> 尺寸/宽高比过滤 -> 合并重叠。
     *
     * @param binary 预处理后的二值图
     * @return 条码候选矩形列表（已合并重叠区域）
     */
    private static List<Rect> findBarcodeRegions(Mat binary) {
        List<Rect> regions = new ArrayList<>();

        // 形态学操作：用 1×15 竖向核做闭运算，连接一维码的竖线特征，形成条码块
        Mat kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(1, 15));
        Mat morph = new Mat();
        Imgproc.morphologyEx(binary, morph, Imgproc.MORPH_CLOSE, kernel);

        // 查找轮廓：只取最外层轮廓，压缩水平/垂直/对角线段以减少点数
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(morph, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            double aspectRatio = (double) rect.width / rect.height;

            // 过滤条件：剔除过小（噪点）、过大（占满整图）、宽高比异常（非条码形状）的轮廓
            if (rect.width > 50 && rect.height > 20 &&
                    rect.width < binary.cols() * 0.9 &&
                    aspectRatio > 0.5 && aspectRatio < 15) {

                // 扩展一点边距，确保条码完整（避免裁剪到边缘导致解码失败）
                int padding = 10;
                // 边界裁剪：保证扩展后的矩形不越出图像范围
                int x = Math.max(0, rect.x - padding);
                int y = Math.max(0, rect.y - padding);
                int w = Math.min(binary.cols() - x, rect.width + padding * 2);
                int h = Math.min(binary.rows() - y, rect.height + padding * 2);

                regions.add(new Rect(x, y, w, h));
            }
        }

        // 合并重叠区域：同一物理条码可能被多个轮廓框选，合并后避免重复解码
        regions = mergeOverlappingRegions(regions);

        morph.release();
        hierarchy.release();
        return regions;
    }

    /**
     * 合并重叠的矩形区域：基于 IOU（交并比）阈值，将高度重叠的矩形合并为外接矩形。
     *
     * @param regions 待合并的候选矩形列表
     * @return 合并后的矩形列表（IOU 超过 0.3 的已合并）
     */
    private static List<Rect> mergeOverlappingRegions(List<Rect> regions) {
        if (regions.size() <= 1) return regions;

        List<Rect> merged = new ArrayList<>();
        // 标记已合并的矩形，避免重复处理
        boolean[] used = new boolean[regions.size()];

        for (int i = 0; i < regions.size(); i++) {
            if (used[i]) continue;
            Rect current = regions.get(i);
            used[i] = true;

            for (int j = i + 1; j < regions.size(); j++) {
                if (used[j]) continue;
                Rect other = regions.get(j);

                // 计算两矩形的交集矩形
                int intersectX = Math.max(current.x, other.x);
                int intersectY = Math.max(current.y, other.y);
                int intersectW = Math.min(current.x + current.width, other.x + other.width) - intersectX;
                int intersectH = Math.min(current.y + current.height, other.y + other.height) - intersectY;

                // 交集面积大于 0 才存在重叠
                if (intersectW > 0 && intersectH > 0) {
                    double intersectArea = intersectW * intersectH;
                    // 并集面积 = 两矩形面积之和 - 交集面积（扣除重复计算的交集部分）
                    double unionArea = current.width * current.height +
                            other.width * other.height - intersectArea;

                    if (intersectArea / unionArea > 0.3) { // IOU > 0.3 合并
                        // 合并为两矩形的外接矩形
                        int x = Math.min(current.x, other.x);
                        int y = Math.min(current.y, other.y);
                        int w = Math.max(current.x + current.width, other.x + other.width) - x;
                        int h = Math.max(current.y + current.height, other.y + other.height) - y;
                        current = new Rect(x, y, w, h);
                        used[j] = true;
                    }
                }
            }
            merged.add(current);
        }
        return merged;
    }

    /**
     * 识别单个区域内的条码：用 ZXing {@code MultiFormatReader} 解码，同时支持一维码与二维码。
     * <p>解码成功后根据角点计算包围盒，并叠加区域偏移还原到原图坐标系。
     *
     * @param image   待解码的区域图像（裁剪自原图）
     * @param offsetX 该区域在原图中的 X 偏移（用于结果坐标还原）
     * @param offsetY 该区域在原图中的 Y 偏移（用于结果坐标还原）
     * @return 识别结果；未识别到返回 null
     */
    private static BarcodeResult decodeBarcode(BufferedImage image, int offsetX, int offsetY) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        // 开启尽力解码模式：牺牲速度换取识别率
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // 同时支持一维码和二维码：限定码制范围，避免盲试全部格式拖慢速度
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.PDF_417, BarcodeFormat.AZTEC
        ));

        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Reader reader = new MultiFormatReader();
            Result result = reader.decode(bitmap, hints);

            // 获取位置信息：由条码角点构成包围盒
            ResultPoint[] points = result.getResultPoints();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = 0, maxY = 0;
            for (ResultPoint p : points) {
                minX = Math.min(minX, (int) p.getX());
                minY = Math.min(minY, (int) p.getY());
                maxX = Math.max(maxX, (int) p.getX());
                maxY = Math.max(maxY, (int) p.getY());
            }

            // 坐标还原：区域局部坐标 + 区域偏移 = 原图坐标
            return new BarcodeResult(
                    result.getText(),
                    result.getBarcodeFormat().toString(),
                    offsetX + minX,
                    offsetY + minY,
                    maxX - minX,
                    maxY - minY
            );
        } catch (NotFoundException e) {
            return null; // 未识别到：该区域无条码，属正常情况
        } catch (Exception e) {
            return null; // 其他异常（如图片格式问题）降级为未识别，保证流程不中断
        }
    }

    /**
     * 全局扫描兜底：用 ZXing {@code GenericMultipleBarcodeReader} 对整图一次性解码多码，
     * 捕获分区定位遗漏的条码（如无竖条特征的二维码）。
     *
     * @param image 待扫描的整图
     * @return 全局扫描结果列表（可能为空，但不为 null）
     */
    private static List<BarcodeResult> scanGlobal(BufferedImage image) {
        List<BarcodeResult> results = new ArrayList<>();

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            // 多码读取器：在整图中尝试解码所有条码
            MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(
                    new MultiFormatReader());
            Result[] resultsArr = multiReader.decodeMultiple(bitmap, hints);

            if (resultsArr != null) {
                for (Result r : resultsArr) {
                    // 取角点最小 X/Y 作为位置锚点（全局扫描无宽高信息，置 0）
                    ResultPoint[] points = r.getResultPoints();
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                    for (ResultPoint p : points) {
                        minX = Math.min(minX, (int) p.getX());
                        minY = Math.min(minY, (int) p.getY());
                    }
                    results.add(new BarcodeResult(
                            r.getText(), r.getBarcodeFormat().toString(),
                            minX, minY, 0, 0));
                }
            }
        } catch (Exception e) {
            // 忽略：全局兜底失败不影响已有分区结果，仅损失本次兜底收益
        }
        return results;
    }

    /**
     * 合并分区解码结果与全局扫描结果并去重：内容相同且位置接近视为同一物理条码。
     *
     * @param local  分区解码结果（优先保留）
     * @param global 全局扫描结果（去重后追加）
     * @return 合并去重后的结果列表
     */
    private static List<BarcodeResult> mergeResults(
            List<BarcodeResult> local, List<BarcodeResult> global) {
        List<BarcodeResult> merged = new ArrayList<>(local);

        for (BarcodeResult g : global) {
            boolean duplicate = false;
            for (BarcodeResult l : local) {
                // 内容相同且位置接近（X/Y 偏移均 < 20px）则认为是同一个条码
                if (g.text.equals(l.text) &&
                        Math.abs(g.x - l.x) < 20 &&
                        Math.abs(g.y - l.y) < 20) {
                    duplicate = true;
                    break;
                }
            }
            // 仅追加非重复的全局结果，避免与分区结果重复计数
            if (!duplicate) merged.add(g);
        }
        return merged;
    }

    /**
     * 测试入口：对指定图片执行识别并按排序输出结果。
     *
     * @param args 未使用
     * @throws Exception 识别过程中发生异常时抛出
     */
    public static void main(String[] args) throws Exception {
        // F:\workspace\workspace-a\盘料图片\20260714185759_158_160.jpg
        // F:\workspace\workspace-a\盘料图片\2026-07-15_111031.png
        // F:\workspace\workspace-a\盘料图片\2026-07-15_093400_979.png
        // F:\workspace\workspace-a\盘料图片\20260714184754_156_160.jpg
        List<BarcodeResult> results = scanOrdered("F:\\workspace\\workspace-a\\盘料图片\\20260714185759_158_160.jpg");

        System.out.println("识别到 " + results.size() + " 个条码（已按从上到下、从左到右排序）：");
        for (int i = 0; i < results.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, results.get(i));
        }
    }
}
