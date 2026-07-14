package com.bajins.ocr.utils.barcode;

import boofcv.abst.fiducial.QrCodeDetector;
import boofcv.alg.fiducial.qrcode.QrCode;
import boofcv.factory.fiducial.ConfigQrCode;
import boofcv.factory.fiducial.FactoryFiducial;
import boofcv.factory.filter.binary.ConfigThreshold;
import boofcv.factory.filter.binary.ThresholdType;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.struct.ConfigLength;
import boofcv.struct.image.GrayU8;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

/**
 * 基于 BoofCV 的多二维码识别器。
 *
 * <p>核心设计：
 * <ul>
 *   <li>直接喂原始灰度图。BoofCV 二维码检测器内部自带局部 Otsu 二值化，且 finder pattern 外观校验
 *       与位采样均读取灰度值；外部预二值化/强锐化会破坏 1:1:3:1:1 比例与模块边界，导致一个码都识别不到。
 *       故严禁在喂入前做二值化或拉普拉斯锐化。</li>
 *   <li>多策略降级：依次尝试默认局部 Otsu、全局 Otsu、局部均值三套阈值，每套再以标准/放宽两档
 *       minimumContour 跑，首个识别到码的策略即胜出，兼容未知图质与不同尺寸二维码。</li>
 *   <li>全部策略失败时汇总各策略 failures 的失败原因分布，便于调参诊断。</li>
 * </ul>
 *
 * <p>参考：https://github.com/lessthanoptimal/BoofCV
 */
public class BoofCvMultiQrReader {

    /**
     * 单次检测策略：名称 + 配置回调
     */
    private record Strategy(String name, Consumer<ConfigQrCode> configurator) {
    }

    /**
     * 按文件路径加载图片并识别其中的所有二维码。
     * <p>等价于先用 {@link UtilImageIO#loadImageNotNull(String)} 加载为 {@link BufferedImage}，
     * 再委托 {@link #detect(BufferedImage)} 执行多策略识别；图片无法读取时由底层加载器抛出异常。
     *
     * @param path 图片文件路径（不可为 null）
     * @return 识别到的二维码列表（可能为空，但不为 null）；QrCode 对象含 message 与 bounds 等信息
     */
    public static List<QrCode> detect(String path) {
        BufferedImage image = UtilImageIO.loadImageNotNull(path);
        return detect(image);
    }

    /**
     * 识别图像中的所有二维码。
     *
     * @param image 源图像，可为彩色或灰度，内部统一转灰度后喂给检测器
     * @return 识别到的二维码列表（可能为空，但不为 null）；QrCode 对象含 message 与 bounds 等信息
     */
    public static List<QrCode> detect(BufferedImage image) {
        Objects.requireNonNull(image, "待识别图像不能为空");
        // 转灰度：检测器在灰度图上工作，切勿喂预二值化图
        GrayU8 gray = ConvertBufferedImage.convertFrom(image, (GrayU8) null);

        List<Strategy> strategies = buildStrategies();
        // 记录每策略的失败原因分布，全部失败时输出诊断
        Map<String, Map<String, Integer>> failureStats = new LinkedHashMap<>();

        for (Strategy strategy : strategies) {
            ConfigQrCode config = new ConfigQrCode();
            // 处理编码不规范的 QR（默认即 true，显式声明以示意图）
            config.considerTransposed = true;
            strategy.configurator().accept(config);

            QrCodeDetector<GrayU8> detector = FactoryFiducial.qrcode(config, GrayU8.class);
            detector.process(gray);
            List<QrCode> detections = detector.getDetections();
            if (!detections.isEmpty()) {
                System.out.println("策略 [" + strategy.name() + "] 识别到 " + detections.size() + " 个二维码");
                // getDetections() 返回内部列表，下次 process 会被回收，立即拷贝隔离
                return new ArrayList<>(detections);
            }
            // 提取失败原因计数（QrCode 对象同样会被回收，仅取 name 字符串避免持有过期引用）
            Map<String, Integer> stats = new LinkedHashMap<>();
            for (QrCode failure : detector.getFailures()) {
                stats.merge(failure.failureCause.name(), 1, Integer::sum);
            }
            failureStats.put(strategy.name(), stats);
        }

        // 所有策略均未识别到，打印失败原因分布辅助调参
        System.out.println("所有策略均未识别到二维码，各策略失败原因分布：");
        failureStats.forEach((name, stats) -> System.out.println("  [" + name + "] " + stats));
        return List.of();
    }

    /**
     * 识别图像中所有二维码的文本内容。
     *
     * @param image 源图像
     * @return 二维码文本列表，顺序与 {@link #detect(BufferedImage)} 一致
     */
    public static List<String> decode(BufferedImage image) {
        List<String> messages = new ArrayList<>();
        for (QrCode qr : detect(image)) {
            messages.add(qr.message);
        }
        return messages;
    }

    /**
     * 构建多策略列表：三套阈值 × 两档最小轮廓，按鲁棒性优先排序。
     *
     * @return 策略列表
     */
    private static List<Strategy> buildStrategies() {
        // 三套阈值配置回调：默认局部 Otsu（最鲁棒）、全局 Otsu（快）、局部均值（低对比度更友好）
        List<Consumer<ConfigQrCode>> thresholds = List.of(
                c -> { /* 保持 ConfigQrCode 默认：BLOCK_OTSU + useOtsu2 + thresholdFromLocalBlocks */ },
                c -> c.threshold = ConfigThreshold.global(ThresholdType.GLOBAL_OTSU),
                c -> {
                    ConfigThreshold localMean = ConfigThreshold.local(ThresholdType.LOCAL_MEAN, 15);
                    localMean.scale = 1.0;
                    c.threshold = localMean;
                }
        );
        String[] thresholdNames = {"BLOCK_OTSU", "GLOBAL_OTSU", "LOCAL_MEAN"};
        // 两档最小轮廓像素：标准 40，放宽 20 兼容小尺寸二维码（finder 为 7×7，每模块约需 3px+）
        double[] minContours = {40, 20};

        List<Strategy> strategies = new ArrayList<>();
        for (int i = 0; i < thresholds.size(); i++) {
            for (double minContour : minContours) {
                final int index = i;
                final double contourFloor = minContour;
                strategies.add(new Strategy(
                        thresholdNames[i] + "/minContour" + (int) contourFloor,
                        c -> {
                            thresholds.get(index).accept(c);
                            c.polygon.detector.minimumContour = ConfigLength.fixed(contourFloor);
                        }
                ));
            }
        }
        return strategies;
    }

    public static void main(String[] args) {
        List<QrCode> detections = detect("F:\\workspace\\workspace-a\\2026-05-05_163050.png");
        System.out.println("共识别到 " + detections.size() + " 个二维码：");
        for (QrCode qr : detections) {
            System.out.println("  内容: " + qr.message);
            // qr.bounds 为四边形顶点，可据此算中心、旋转角等
        }
    }
}
