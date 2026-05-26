package com.bajins.ocr.utils.rustpaddleocr;

import com.sun.jna.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;


/**
 * https://github.com/zibo-chen/paddle-ocr-capi/blob/master/include/ocr_capi.h
 */
public interface OcrCapi extends Library {
    // 加载名为 ocr_capi 的动态库（Windows加载 ocr_capi.dll，macOS为 libocr_capi.dylib，Linux为 libocr_capi.so）
    OcrCapi INSTANCE = loadLibrary(null, OcrCapi.class);

    /**
     * 加载 OCR 动态库
     * <p>
     * https://github.com/zibo-chen/paddle-ocr-capi/releases
     */
    static <T extends Library> T loadLibrary(String libDir, Class<T> clazz) {
        String libName = "rustpaddleocr/";
        if (Platform.isMac()) {
            libName += "libocr_capi.dylib";
        } else if (Platform.isLinux()) {
            libName += "libocr_capi.so";
        } else if (Platform.isWindows()) {
            libName += "ocr_capi-windows-x64.dll";
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

    // ==========================================
    // 适配 C 语言 size_t 的跨平台包装类
    // 在C语言中，size_t的大小在不同系统上是变化的(32位系统占4字节，64位系统占8字节)。
    // 为了保证内存严格对齐，防止JVM崩溃，正确的做法是利用JNA的IntegerType 自定义一个SizeT类型。
    // ==========================================
    class SizeT extends IntegerType {
        public SizeT() {
            // Native.SIZE_T_SIZE 会自动根据当前系统架构判断是 4 还是 8
            // true 表示无符号数
            super(Native.SIZE_T_SIZE, 0, true);
        }

        public SizeT(long value) {
            super(Native.SIZE_T_SIZE, value, true);
        }
    }

    // ==========================================
    // 1. 句柄类型 (Opaque Pointers)
    // ==========================================
    class OcrEngineHandle extends PointerType {
    }

    class DetModelHandle extends PointerType {
    }

    class RecModelHandle extends PointerType {
    }

    class OriModelHandle extends PointerType {
    }

    // ==========================================
    // 2. 数据结构 (Structs)
    // ==========================================
    @Structure.FieldOrder({"x", "y", "width", "height"})
    class Bbox extends Structure {
        public int x;
        public int y;
        public int width;
        public int height;

        public Bbox() {
        }

        public Bbox(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"text", "confidence", "bbox"})
    class OcrResult extends Structure {
        // 识别出的文本
        public Pointer text; // C中的 char*
        // 置信度
        public float confidence;
        // 嵌套的边界框结构体
        public Bbox bbox;

        public OcrResult() {
        }

        public OcrResult(Pointer p) {
            super(p);
            read();
        }

        // 将底层 char* 按 UTF-8 转为 Java 字符串
        public String getText() {
            return text == null ? "" : text.getString(0, "UTF-8");
        }
    }

    @Structure.FieldOrder({"items", "count"})
    class OcrResultList extends Structure {
        public Pointer items; // 指向 OcrResult 数组的指针
        public SizeT count;

        public static class ByValue extends OcrResultList implements Structure.ByValue {
        }

        // 辅助方法：将指针转换为结构体数组
        public OcrResult[] getResults() {
            if (count.intValue() == 0 || items == null) return new OcrResult[0];
            OcrResult ref = new OcrResult(items);
            return (OcrResult[]) ref.toArray(count.intValue());
        }
    }

    @Structure.FieldOrder({"items", "count"})
    class DetResultList extends Structure {
        public Pointer items;      // 指向 Bbox 数组的指针 (OcrBBox*)
        public SizeT count;

        public static class ByValue extends DetResultList implements Structure.ByValue {
        }

        public Bbox[] getItems() {
            if (items == null || count.longValue() == 0) return new Bbox[0];
            Bbox reference = new Bbox(items);
            return (Bbox[]) reference.toArray(count.intValue());
        }
    }

    @Structure.FieldOrder({"text", "confidence"})
    class RecResult extends Structure {
        public String text;
        public float confidence;

        public static class ByValue extends RecResult implements Structure.ByValue {
        }
    }

    @Structure.FieldOrder({"class_idx", "angle", "confidence"})
    class OriResult extends Structure {
        public SizeT class_idx;
        public int angle;
        public float confidence;

        public static class ByValue extends OriResult implements Structure.ByValue {
        }
    }

    // JNA中 ByValue 传值结构体的大小必须与 C 层完全一致，否则可能导致崩溃
    @Structure.FieldOrder({"backend", "thread_count", "precision", "det_max_side_len",
            "det_box_threshold", "det_score_threshold", "rec_min_score",
            "min_result_confidence", "enable_parallel"})
    class OcrConfig extends Structure {
        public int backend;
        public int thread_count;
        public int precision;
        public int det_max_side_len;
        public float det_box_threshold;
        public float det_score_threshold;
        public float rec_min_score;
        public float min_result_confidence;
        public int enable_parallel;

        public static class ByValue extends OcrConfig implements Structure.ByValue {
        }
    }


    // ==========================================
    // C API 函数映射 (Functions)
    // ==========================================

    // 配置初始化函数 (按值返回 ByValue)
    OcrConfig.ByValue ocr_config_default();

    OcrConfig.ByValue ocr_config_fast();

    OcrConfig.ByValue ocr_config_gpu();

    Pointer ocr_get_last_error();  // 返回 char*，为了防止泄露必须返回 Pointer 手动释放

    void ocr_free_string(Pointer error);

    String ocr_version();

    // 引擎创建与销毁 (Opaque 句柄在 Java 中统一使用 Pointer 表达)
    OcrEngineHandle ocr_engine_create(String det_path, String rec_path, String keys_path, OcrConfig config);

    OcrEngineHandle ocr_engine_create_with_ori(String det_path, String rec_path, String keys_path, String ori_path, OcrConfig config);

    // config 传 Pointer.NULL 可使用默认配置
    OcrEngineHandle ocr_engine_create(String det_model, String rec_model, String keys_file, Pointer config);

    void ocr_engine_destroy(OcrEngineHandle engine);

    void ocr_engine_destroy(Pointer engine);

    // 高级识别 API (按值返回 ResultList)
    OcrResultList.ByValue ocr_engine_recognize_file(Pointer engine, String file_path);

    OcrResultList.ByValue ocr_engine_recognize_file(OcrEngineHandle engine, String path);

    OcrResultList.ByValue ocr_engine_recognize_rgb(Pointer engine, byte[] rgb_data, int width, int height);

    OcrResultList.ByValue ocr_engine_recognize_rgba(Pointer engine, byte[] rgba_data, int width, int height);

    // 使用 byte[] 直接传递图像内存数据
    OcrResultList.ByValue ocr_engine_recognize_rgb(OcrEngineHandle engine, byte[] rgb_data, long width, long height);

    OcrResultList.ByValue ocr_engine_recognize_rgba(OcrEngineHandle engine, byte[] rgba_data, long width, long height);

    // 优化：使用 ByteBuffer 替代 byte[] 实现零拷贝
    OcrResultList.ByValue ocr_engine_recognize_rgb(OcrEngineHandle handle, ByteBuffer rgb_data, int width, int height);

    OcrResultList.ByValue ocr_engine_recognize_rgba(OcrEngineHandle handle, ByteBuffer rgba_data, int width, int height);

    // 释放结果内存 (C函数要求传入 &result 指针，通过 JNA 的 Pointer 传递)
    void ocr_result_list_free(Pointer result);


    // Ori 方向分类模型 API
    OriModelHandle ocr_ori_model_create(String model_path, OcrConfig config);

    void ocr_ori_model_destroy(OriModelHandle handle);

    OriResult.ByValue ocr_ori_model_classify_file(OriModelHandle handle, String image_path);

    // 显式内存释放（传入的是结构体指针，对应 Java 的普通 Structure 对象）
    void ocr_result_list_free(OcrResultList result); // JNA 自动传递结构体指针

    /// ---------------------------

    // 创建检测模型
    Pointer ocr_det_model_create(String det_path, OcrConfig config);

    /**
     * 销毁检测模型
     *
     * @param det 模型句柄
     */
    void ocr_det_model_destroy(Pointer det);

    // 使用检测模型检测文本区域
    DetResultList.ByValue ocr_det_model_detect(Pointer det, byte[] rgb_data, int width, int height);

    void ocr_det_result_free(DetResultList result);

    // 底层识别模型 API
    Pointer ocr_rec_model_create(String rec_path, String keys_path, OcrConfig config);

    void ocr_rec_model_destroy(Pointer rec);

    RecResult.ByValue ocr_rec_model_recognize(Pointer rec, byte[] rgb_data, int width, int height);

    void ocr_rec_result_free(RecResult result);
}