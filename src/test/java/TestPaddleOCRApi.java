import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

// https://github.com/PaddleOCRCore/PaddleOCRApi
public class TestPaddleOCRApi {

    /**
     * PaddleOCR.dll C++接口
     * <p>
     * 以下dll文件为必须
     * mkldnn.dll
     * mklml.dll
     * paddle_inference.dll
     * PaddleOCR.dll
     * phi.dll
     * common.dll
     * libiomp5md.dll
     */
    // 定义 DLL 接口，对应 PaddleOCR.dll 中的导出函数
    public interface PaddleOCR extends StdCallLibrary {
        // 加载 PaddleOCR.dll
        PaddleOCR INSTANCE = loadLibrary(null, PaddleOCR.class);

        static <T extends Library> T loadLibrary(String libDir, Class<T> clazz) {
            String libName = "win32-x86-64/";
            if (Platform.isMac()) {
                libName += "PaddleOCR.dylib";
            } else if (Platform.isLinux()) {
                libName += "PaddleOCR.so";
            } else if (Platform.isWindows()) {
                libName += "PaddleOCR.dll";
            } else {
                throw new RuntimeException("Unsupported platform: " + System.getProperty("os.name"));
            }
            File dll;
            if (libDir != null && !libDir.isEmpty() && Files.exists(Paths.get(libDir))) {
                Path path = Paths.get(libDir);
                if (!Files.isDirectory(path)) {
                    path = path.getParent();
                }
                dll = path.resolve(libName).toFile();
            } else {
                try {
                    dll = Native.extractFromResourcePath(libName);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            // 为关键库设置专用路径
            // NativeLibrary.addSearchPath("PaddleOCR", dll.getParent());
            // Windows设置多个库路径，用分号分隔
            System.setProperty("jna.library.path", dll.getParent());
            if (Platform.isWindows()) {
                // 设置 Windows DLL 搜索目录（处理依赖）用 Kernel32.SetDllDirectory 扩展搜索路径（Win32 API）
                // 需要 JNA 的 platform.jar
                //com.sun.jna.platform.win32.Kernel32.INSTANCE.SetDllDirectory(dll.getParent());
                NativeLibrary.getInstance("kernel32")
                        .getFunction("SetDllDirectoryA")
                        .invokeInt(new Object[]{dll.getParent()});
            }
            // 在加载 DLL 前，动态添加路径到 java.library.path
        /*String libraryPath = System.getProperty("java.library.path");
        System.setProperty("java.library.path", dll.getParent() + File.pathSeparator + libraryPath);*/
            return Native.load(dll.getAbsolutePath(), clazz);
        }

        // 开启/关闭日志
        void EnableLog(boolean useLog);

        // 设置返回结果格式 (true: JSON, false: String)
        void EnableJsonResult(boolean enable);

        // 初始化引擎 (JSON 配置方式)
        boolean Initjson(String det_infer, String cls_infer, String rec_infer, String parameterjson);

        // 识别图片
        Pointer Detect(String imageFile);

        // 释放 Detect 返回的结果缓冲区
        void FreeResultBuffer(Pointer resultPtr);

        // 释放引擎
        void FreeEngine();
    }

    public static void main(String[] args) {
        try {
            // 获取当前工作目录
            String rootDir = System.getProperty("user.dir");

            // 模型路径 (请根据实际存放位置修改)
            String detModel = rootDir + "\\model\\ocr\\PaddleOCR\\PP-OCRv5_server_det_infer";
            String clsModel = rootDir + "\\model\\ocr\\PaddleOCR\\PP-LCNet_x1_0_textline_ori_infer";
            String recModel = rootDir + "\\model\\ocr\\PaddleOCR\\PP-OCRv5_server_rec_infer";

            // 初始化参数 JSON
            Map<String, Object> ocrParameter = getOCRParameter();
            ocrParameter.put("cpu_math_library_num_threads", 10);
            ocrParameter.put("cpu_mem", 4000);
            ocrParameter.put("enable_mkldnn", true);
            String configJson = new ObjectMapper().writeValueAsString(ocrParameter);

            System.out.println("=== PaddleOCR Java Demo ===");
            System.out.println("正在初始化 OCR 引擎...");

            PaddleOCR.INSTANCE.EnableLog(false);

            // 初始化
            boolean inited = PaddleOCR.INSTANCE.Initjson(detModel, clsModel, recModel, configJson);

            if (!inited) {
                System.err.println("OCR 初始化失败！请确认以下事项：");
                System.err.println("1. PaddleOCR.dll 及其依赖项在系统路径或当前目录下");
                System.err.println("2. 模型路径正确: " + detModel);
                return;
            }

            // 设置返回格式为纯文本
            PaddleOCR.INSTANCE.EnableJsonResult(false);

            // 执行 OCR 识别
            Pointer resultPtr = PaddleOCR.INSTANCE.Detect("F:\\workspace\\workspace-a\\2026-05-05_163050.png");
            String result = "";
            if (resultPtr != null) {
                result = resultPtr.getString(0, "UTF-8");
                PaddleOCR.INSTANCE.FreeResultBuffer(resultPtr);
            }
            System.out.println("识别内容: \n" + result);

            // 遍历 images 目录下的图片
            /*File imageDir = new File("target/classes/images");
            if (imageDir.exists() && imageDir.isDirectory()) {
                File[] images = imageDir.listFiles((dir, name) -> {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".bmp");
                });

                if (images != null && images.length > 0) {
                    for (File img : images) {
                        System.out.println("\n处理图片: " + img.getName());
                        long startTime = System.currentTimeMillis();

                        // 执行 OCR 识别
                        Pointer resultPtr = PaddleOCR.INSTANCE.Detect(img.getAbsolutePath());
                        String result = "";
                        if (resultPtr != null) {
                            result = resultPtr.getString(0, "UTF-8");
                            PaddleOCR.INSTANCE.FreeResultBuffer(resultPtr);
                        }

                        long endTime = System.currentTimeMillis();
                        System.out.println("OCR 耗时: " + (endTime - startTime) + "ms");
                        System.out.println("识别内容: \n" + result);
                    }
                } else {
                    System.out.println("在 images 目录下未找到图片文件。");
                }
            } else {
                System.err.println("未找到图片目录: " + imageDir.getAbsolutePath());
            }

            System.out.println("\n程序运行完毕，按回车键退出...");
            new Scanner(System.in).nextLine();*/

            // 释放引擎
            // PaddleOCR.INSTANCE.FreeEngine();

        } catch (UnsatisfiedLinkError e) {
            System.err.println("无法加载 DLL: " + e.getMessage());
            System.err.println("请确保 PaddleOCR.dll 及其依赖库在当前目录或 PATH 中。");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * PaddleOCR.dll C++识别参数 (对应 struct OCRParameter)
     * <p>
     * https://github.com/PaddleOCRCore/PaddleOCRApi/blob/main/Demo/CPP/include/AI_Parameter.h
     */
    public static Map<String, Object> getOCRParameter() {
        Map<String, Object> map = new HashMap<>();

        // 前向相关
        map.put("det", true);           // 是否执行文字检测
        map.put("rec", true);           // 是否执行文字识别
        map.put("cls", false);          // 是否执行文字方向分类

        // 通用参数
        map.put("use_gpu", false);      // 是否使用GPU
        map.put("gpu_id", 0);           // GPU id，使用GPU时有效
        map.put("gpu_mem", 4000);       // 使用GPU时内存
        map.put("use_tensorrt", false); // 使用GPU预测时，是否启动tensorrt

        map.put("cpu_mem", 0);          // CPU内存占用上限，单位MB。-1表示不限制
        map.put("cpu_threads", 10);     // CPU预测时的线程数，在机器核数充足的情况下，该值越大，预测速度越快，默认10
        map.put("enable_mkldnn", true); // 是否使用mkldnn库

        // 检测模型相关
        map.put("max_side_len", 960);         // 输入图像长宽大于960时，等比例缩放图像，使得图像最长边为960
        map.put("det_db_thresh", 0.3f);       // 用于过滤DB预测的二值化图像，设置为0.-0.3对结果影响不明显
        map.put("det_db_box_thresh", 0.5f);   // DB后处理过滤box的阈值，如果检测存在漏框情况，可酌情减小
        map.put("det_db_unclip_ratio", 1.6f); // 表示文本框的紧致程度，越小则文本框更靠近文本
        map.put("use_dilation", false);       // 是否在输出映射上使用膨胀
        map.put("det_db_score_mode", true);   // true:使用多边形框计算bbox score，false:使用矩形框计算。矩形框计算速度更快，多边形框对弯曲文本区域计算更准确。
        map.put("visualize", false);          // 是否对结果进行可视化，为true时，预测结果会保存在output文件夹下和输入图像同名的图像上。

        // 方向分类器相关
        map.put("use_angle_cls", false); // 是否使用方向分类器
        map.put("cls_thresh", 0.9f);     // 方向分类器的得分阈值
        map.put("cls_batch_num", 1);     // 方向分类器批量识别数量

        // 识别模型相关
        map.put("rec_batch_num", 10);    // 文字识别模型批量识别数量
        map.put("rec_img_h", 48);        // 识别模型输入图像高度
        map.put("rec_img_w", 320);       // 识别模型输入图像宽度

        return map;
    }

    /**
     * 表格识别参数 (对应 struct TableParameter : OCRParameter)
     * 包含了 OCRParameter 的所有字段
     */
    public static Map<String, Object> getTableParameter() {
        // 继承自 OCRParameter，所以先获取基础Map
        Map<String, Object> map = new HashMap<>(getOCRParameter());

        // 添加表格特有参数
        map.put("table_max_len", 488);      // 输入图像长宽大于488时，等比例缩放图像,默认488
        map.put("merge_empty_cell", true);  // 是否合并空单元格
        map.put("table_batch_num", 1);      // 批量识别数量

        return map;
    }

    /**
     * OCR动态修改参数 (对应 struct SyncParameter)
     */
    public static Map<String, Object> getSyncParameter() {
        Map<String, Object> map = new HashMap<>();

        map.put("d_det", true);                 // 动态修改是否检测
        map.put("d_rec", true);                 // 输入图像长宽 (原文注释如此，虽然变量名看起来像bool，但注释写的是长宽，根据C++ bool类型此处设为true)
        map.put("d_det_db_box_thresh", 0.5f);   // 表示文本框的紧致程度
        map.put("d_det_db_unclip_ratio", 1.6f); // 表示文本框的紧致程度，越小则文本框更靠近文本
        map.put("d_max_side_len", 960);         // 输入图像长宽
        map.put("d_det_db_thresh", 0.3f);       // DB后处理过滤box的阈值

        return map;
    }
}
