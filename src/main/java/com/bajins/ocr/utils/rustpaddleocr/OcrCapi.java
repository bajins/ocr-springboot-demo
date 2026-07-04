package com.bajins.ocr.utils.rustpaddleocr;

import com.sun.jna.*;

import java.nio.ByteBuffer;

/**
 * rust-paddle-ocr C API 的 JNA 绑定。
 * <p>
 * 仅声明原生结构体与函数映射，库加载逻辑见 {@link OcrNativeLibrary}。
 * <p>
 * https://github.com/zibo-chen/paddle-ocr-capi/blob/master/include/ocr_capi.h
 */
public interface OcrCapi extends Library {

    /**
     * 单例绑定实例，启动时从资源路径加载原生库。
     * 库不在资源路径时抛出 {@link OcrException}（触发 {@link ExceptionInInitializerError}）。
     */
    OcrCapi INSTANCE = OcrNativeLibrary.load(null, OcrCapi.class);

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
    // 3. C API 函数映射 (Functions)
    // ==========================================

    // 配置初始化函数 (按值返回 ByValue)
    OcrConfig.ByValue ocr_config_default();

    OcrConfig.ByValue ocr_config_fast();

    OcrConfig.ByValue ocr_config_gpu();

    Pointer ocr_get_last_error();  // 返回 char*，为了防止泄露必须返回 Pointer 手动释放

    void ocr_free_string(Pointer error);

    String ocr_version();

    // 引擎创建与销毁 (config 传 OcrConfig.ByValue，由 PaddleOcrEngine.Config.toNative() 构造)
    OcrEngineHandle ocr_engine_create(String det_path, String rec_path, String keys_path, OcrConfig config);

    OcrEngineHandle ocr_engine_create_with_ori(String det_path, String rec_path, String keys_path, String ori_path, OcrConfig config);

    void ocr_engine_destroy(OcrEngineHandle engine);

    // 高级识别 API (按值返回 ResultList)
    OcrResultList.ByValue ocr_engine_recognize_file(OcrEngineHandle engine, String path);

    // 零拷贝识别 RGB 图像数据（ByteBuffer 必须为 Direct）
    OcrResultList.ByValue ocr_engine_recognize_rgb(OcrEngineHandle handle, ByteBuffer rgb_data, int width, int height);

    // 零拷贝识别 RGBA 图像数据（ByteBuffer 必须为 Direct）
    OcrResultList.ByValue ocr_engine_recognize_rgba(OcrEngineHandle handle, ByteBuffer rgba_data, int width, int height);

    // 释放结果内存 (C函数要求传入 &result 指针，通过 JNA 的 Pointer 传递)
    void ocr_result_list_free(Pointer result);

    // 显式内存释放（传入的是结构体指针，对应 Java 的普通 Structure 对象）
    void ocr_result_list_free(OcrResultList result); // JNA 自动传递结构体指针


    /// ---------------------------
    // Ori 方向分类模型 API
    OriModelHandle ocr_ori_model_create(String model_path, OcrConfig config);

    void ocr_ori_model_destroy(OriModelHandle handle);

    OriResult.ByValue ocr_ori_model_classify_file(OriModelHandle handle, String image_path);

    /// ---------------------------
    // 底层检测模型 API
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
