import com.bajins.ocr.utils.rustpaddleocr.*;


import java.awt.*;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * OCR Java 示例
 * 展示如何使用 Java 调用 OCR C API
 * <p>
 * https://github.com/zibo-chen/paddle-ocr-capi
 * https://github.com/zibo-chen/rust-paddle-ocr
 */
public class RustPaddleOcrExample {

    public static void main(String[] args) {
        System.out.println("PaddleOCR 库版本: " + PaddleOcrEngine.getVersion());

        // 1. 初始化快速 GPU 引擎配置（如果是 Mac 自动走 Metal，Windows 自动走 OpenCL）
        PaddleOcrEngine.Config config = PaddleOcrEngine.Config.gpu();

        String detModel = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\PP-OCRv5_mobile_det.mnn";
//        String detModel = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\ch_PP-OCRv5_det_server.mnn";
        String recModel = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\PP-OCRv5_mobile_rec.mnn";
//        String recModel = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\ch_PP-OCRv5_rec_server.mnn";
        String keysFile = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\ppocr_keys_v5.txt";
        String oriModel = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\PP-LCNet_x1_0_doc_ori.mnn";
//        String oriModel = "F:\\workspace\\workspace-a\\rust-paddle-ocr\\models\\ch_PP-LCNet_x1_0_textline_ori_cls_server.mnn";
        String testImg = "F:\\workspace\\workspace-a\\2026-05-05_163050.png";

        // 2. 使用带有自动旋转矫正的 OCR 引擎 (通过 Java 7+ try-with-resources 自动安全释放句柄)
        try (PaddleOcrEngine.Engine ocr = new PaddleOcrEngine.Engine(detModel, recModel, keysFile, oriModel, config)) {

            // 模式 A：直接识别本地文件
            System.out.println("--- 正在识别本地文件 ---");
            List<PaddleOcrEngine.Result> results = ocr.recognizeFile(testImg);
            for (PaddleOcrEngine.Result res : results) {
                System.out.println(res);
            }

            // 模式 B：极其硬核的【零拷贝】图像数据识别 (大幅降低 GC 压力，提升每秒推理帧数 FPS)
            System.out.println("\n--- 正在演示零拷贝内存块识别 ---");
            int width = 1920;
            int height = 1080;
            int bufferSize = width * height * 3; // RGB 3通道

            // 分配一个 JVM 堆外内存 (直接对应 C 语言的 malloc 指针区)
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(bufferSize);

            // 模拟将你的摄像头数据、视频帧、或 BufferedImage 的像素直接注入进 directBuffer
            // directBuffer.put( ... 像素数据 ... );
            directBuffer.flip(); // 重置指针以便底层读取

            // 调用零拷贝识别方法
            List<PaddleOcrEngine.Result> streamResults = ocr.recognizeRgb(directBuffer, width, height);
            System.out.println("成功处理零拷贝内存帧，识别出条目数量: " + streamResults.size());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}