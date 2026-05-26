package com.bajins.ocr.utils;

import boofcv.abst.fiducial.QrCodeDetector;
import boofcv.alg.fiducial.qrcode.QrCode;
import boofcv.factory.fiducial.ConfigQrCode;
import boofcv.factory.fiducial.FactoryFiducial;
import boofcv.factory.filter.binary.ConfigThreshold;
import boofcv.factory.filter.binary.ThresholdType;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.struct.image.GrayU8;
import net.sourceforge.tess4j.util.ImageIOHelper;
import nu.pattern.OpenCV;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

/**
 * https://github.com/lessthanoptimal/BoofCV
 */
public class BoofCvMultiQrReader {

    static {
        // 加载OpenCV本地库
        OpenCV.loadShared();
    }

    public static void main(String[] args) {
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
        BufferedImage originalImage = UtilImageIO.loadImageNotNull(new File(path).getAbsolutePath());

        // 2. 预处理（使用OpenCV增强对比度、去噪）
        Mat matImage = bufferedImageToMat(originalImage);
        Mat processedImage = preprocessImage(matImage);
        BufferedImage enhancedImage = matToBufferedImage(processedImage);

        // 3. 转换为BoofCV的灰度图
        GrayU8 gray = ConvertBufferedImage.convertFrom(enhancedImage, (GrayU8) null);

        // 配置：可调 considerTransposed、threshold 等
        ConfigQrCode config = new ConfigQrCode();
        config.considerTransposed = true;   // 处理一些编码不规范的 QR
        // 针对模糊图像，可以调整阈值配置
        config.threshold.type = ThresholdType.BLOCK_OTSU; // ConfigThreshold.type.GLOBAL_OTSU

        QrCodeDetector<GrayU8> detector = FactoryFiducial.qrcode(config, GrayU8.class);
        detector.process(gray);

        // 成功识别的 QR 码
        List<QrCode> detections = detector.getDetections();
        System.out.println("识别到 " + detections.size() + " 个 QR 码：");
        for (QrCode qr : detections) {
            System.out.println("  内容: " + qr.message);
            // qr.bounds 是四边形顶点，可以算中心、旋转角等
        }

        // 识别“差点就成功”的码（可用来调参）
        List<QrCode> failures = detector.getFailures();
        System.out.println("失败候选: " + failures.size());
        for (QrCode qr : failures) {
            if (qr.failureCause.ordinal() >= QrCode.Failure.ERROR_CORRECTION.ordinal()) {
                // 有可能是真码，只是纠错失败，可以考虑宽松模式或二次处理
            }
        }
    }

    /**
     * 图像预处理：增强对比度、去噪、倾斜校正
     */
    private static Mat preprocessImage(Mat image) {
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat sharpened = new Mat();
        Mat binary = new Mat();

        // 1. 转灰度
        if (image.channels() > 1) {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = image.clone();
        }

        // 2. 高斯模糊去噪
        Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);

        // 3. 锐化增强边缘
        Mat sharpenKernel = new Mat(3, 3, CvType.CV_32F) {
            {
                put(0, 0, -1);
                put(0, 1, -1);
                put(0, 2, -1);
                put(1, 0, -1);
                put(1, 1, 9);
                put(1, 2, -1);
                put(2, 0, -1);
                put(2, 1, -1);
                put(2, 2, -1);
            }
        };
        Imgproc.filter2D(blurred, sharpened, -1, sharpenKernel);

        // 4. 自适应阈值二值化（比全局阈值更适合光照不均的标签）
        Imgproc.adaptiveThreshold(sharpened, binary, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 15, 2);

        // 5. 倾斜校正（可选，如果检测到倾斜）
//         Mat deskewed = ImageIOHelper.deskewImage(binary);

        return binary; // 或返回 deskewed
    }

    // BufferedImage转Mat
    private static Mat bufferedImageToMat(BufferedImage bi) {
        Mat mat = new Mat(bi.getHeight(), bi.getWidth(), CvType.CV_8UC3);
        byte[] data = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, data);
        return mat;
    }

    // Mat转BufferedImage
    private static BufferedImage matToBufferedImage(Mat mat) {
        byte[] data = new byte[mat.cols() * mat.rows() * (int)mat.elemSize()];
        mat.get(0, 0, data);
        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setDataElements(0, 0, mat.cols(), mat.rows(), data);
        return image;
    }
}