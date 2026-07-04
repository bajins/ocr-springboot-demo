package com.bajins.ocr.utils.rustpaddleocr;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * rust-paddle-ocr 的高层 Java 封装。
 * <p>
 * 屏蔽 JNA 细节，提供类型安全的枚举、流式配置器与 {@link AutoCloseable} 资源管理。
 * <p>
 * 线程安全：{@link Engine} 与 {@link OriModel} 的识别方法均以实例锁同步，
 * 同一实例可被多线程并发调用；若需要更高并发，请为每个线程创建独立实例。
 * <p>
 * https://github.com/zibo-chen/paddle-ocr-capi
 * https://github.com/zibo-chen/rust-paddle-ocr
 */
public class PaddleOcrEngine {

    // ==========================================
    // 1. 类型安全的枚举
    // ==========================================
    public enum Backend {
        CPU(0),
        METAL(1),// macOS GPU
        OPENCL(2),// Android/Linux
        VULKAN(3);// 跨平台 GPU
        public final int val;

        Backend(int val) {
            this.val = val;
        }
    }

    public enum Precision {
        NORMAL(0), LOW(1), HIGH(2);
        public final int val;

        Precision(int val) {
            this.val = val;
        }
    }

    // ==========================================
    // 2. 流式参数配置器（全字段可调，均带默认值）
    // ==========================================
    public static class Config {
        private Backend backend = Backend.CPU;
        private Precision precision = Precision.NORMAL;
        private int detMaxSideLen = 960;
        private int threadCount = 4;
        private float detBoxThreshold = 0.5f;
        private float detScoreThreshold = 0.3f;
        private float recMinScore = 0.3f;
        private float minResultConfidence = 0.5f;
        private boolean enableParallel = true;

        public static Config defaultConf() {
            return new Config();
        }

        public static Config fast() {
            Config c = new Config();
            c.precision = Precision.LOW;
            c.detMaxSideLen = 640;
            return c;
        }

        public static Config gpu() {
            Config c = new Config();
            c.backend = Platform.isMac() ? Backend.METAL : Backend.OPENCL;
            return c;
        }

        public Config backend(Backend backend) {
            this.backend = backend;
            return this;
        }

        public Config precision(Precision precision) {
            this.precision = precision;
            return this;
        }

        public Config detMaxSideLen(int detMaxSideLen) {
            this.detMaxSideLen = detMaxSideLen;
            return this;
        }

        public Config threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        public Config detBoxThreshold(float detBoxThreshold) {
            this.detBoxThreshold = detBoxThreshold;
            return this;
        }

        public Config detScoreThreshold(float detScoreThreshold) {
            this.detScoreThreshold = detScoreThreshold;
            return this;
        }

        public Config recMinScore(float recMinScore) {
            this.recMinScore = recMinScore;
            return this;
        }

        public Config minResultConfidence(float minResultConfidence) {
            this.minResultConfidence = minResultConfidence;
            return this;
        }

        public Config enableParallel(boolean enableParallel) {
            this.enableParallel = enableParallel;
            return this;
        }

        // 转为底层 JNA 结构
        OcrCapi.OcrConfig toNative() {
            OcrCapi.OcrConfig nc = new OcrCapi.OcrConfig();
            nc.backend = this.backend.val;
            nc.thread_count = this.threadCount;
            nc.precision = this.precision.val;
            nc.det_max_side_len = this.detMaxSideLen;
            nc.det_box_threshold = this.detBoxThreshold;
            nc.det_score_threshold = this.detScoreThreshold;
            nc.rec_min_score = this.recMinScore;
            nc.min_result_confidence = this.minResultConfidence;
            nc.enable_parallel = this.enableParallel ? 1 : 0;
            return nc;
        }
    }

    /**
     * OCR 识别结果（不可变值对象）。
     *
     * @param text      识别出的文本
     * @param confidence 置信度（0~1）
     * @param x         边界框左上角 x
     * @param y         边界框左上角 y
     * @param width     边界框宽
     * @param height    边界框高
     */
    public record Result(String text, float confidence, int x, int y, int width, int height) {
        @Override
        public String toString() {
            return String.format("文本: %s, 置信度: %.2f%%, 坐标: (%d,%d,%d,%d)",
                    text, confidence * 100, x, y, width, height);
        }
    }

    /**
     * 方向分类结果（不可变值对象）。
     *
     * @param classIdx   方向类别索引
     * @param angle      旋转角度
     * @param confidence 置信度（0~1）
     */
    public record OrientationResult(int classIdx, int angle, float confidence) {
    }


    // ==========================================
    // 3. OCR 引擎封装 (核心)
    // ==========================================
    public static class Engine implements AutoCloseable {
        private OcrCapi.OcrEngineHandle handle;

        /**
         * 初始化 OCR 引擎。
         *
         * @param detPath  检测模型路径
         * @param recPath  识别模型路径
         * @param keysPath 字典文件路径
         * @param config   引擎配置；为 null 时使用默认配置
         */
        public Engine(String detPath, String recPath, String keysPath, Config config) {
            handle = OcrCapi.INSTANCE.ocr_engine_create(
                    detPath, recPath, keysPath, config != null ? config.toNative() : null
            );
            ensureHandle(handle, "创建 OCR 引擎");
        }

        /**
         * 初始化带方向分类的 OCR 引擎。
         *
         * @param detPath  检测模型路径
         * @param recPath  识别模型路径
         * @param keysPath 字典文件路径
         * @param oriPath  方向分类模型路径
         * @param config   引擎配置；为 null 时使用默认配置
         */
        public Engine(String detPath, String recPath, String keysPath, String oriPath, Config config) {
            handle = OcrCapi.INSTANCE.ocr_engine_create_with_ori(
                    detPath, recPath, keysPath, oriPath, config != null ? config.toNative() : null
            );
            ensureHandle(handle, "创建 OCR 引擎");
        }

        /**
         * 识别本地图片文件。
         *
         * @param filePath 图片绝对路径
         * @return 识别结果列表
         */
        public synchronized List<Result> recognizeFile(String filePath) {
            OcrCapi.OcrResultList.ByValue raw = OcrCapi.INSTANCE.ocr_engine_recognize_file(handle, filePath);
            return extractAndFree(raw);
        }

        /**
         * 零拷贝识别 RGB 图像数据。
         *
         * @param directBuffer 必须是 ByteBuffer.allocateDirect(size) 分配的直接缓冲，容量需不小于 width*height*3
         * @param width        图像宽度（像素）
         * @param height       图像高度（像素）
         * @return 识别结果列表
         */
        public synchronized List<Result> recognizeRgb(ByteBuffer directBuffer, int width, int height) {
            if (!directBuffer.isDirect()) {
                throw new OcrException("零拷贝识别要求 ByteBuffer 必须为 Direct");
            }
            if (width <= 0 || height <= 0) {
                throw new OcrException("图像尺寸非法: width=" + width + ", height=" + height);
            }
            // 用 capacity 而非 remaining 校验，避免调用方 flip() 导致 remaining 归零的误判
            long required = (long) width * height * 3;
            if (directBuffer.capacity() < required) {
                throw new OcrException("缓冲区容量不足: 需要 " + required + " 字节, 实际 " + directBuffer.capacity());
            }
            OcrCapi.OcrResultList.ByValue raw = OcrCapi.INSTANCE.ocr_engine_recognize_rgb(handle, directBuffer, width, height);
            return extractAndFree(raw);
        }

        /**
         * 内部处理结果列表并释放底层内存。
         *
         * @param raw 原生返回的结果列表（按值）
         * @return 转换后的 Java 结果列表
         */
        private List<Result> extractAndFree(OcrCapi.OcrResultList.ByValue raw) {
            List<Result> resList = new ArrayList<>();
            try {
                int count = raw.count != null ? raw.count.intValue() : 0;
                if (count > 0 && raw.items != null) {
                    OcrCapi.OcrResult itemRef = new OcrCapi.OcrResult(raw.items);
                    OcrCapi.OcrResult[] items = (OcrCapi.OcrResult[]) itemRef.toArray(count);

                    for (OcrCapi.OcrResult item : items) {
                        resList.add(new Result(
                                item.getText(),
                                item.confidence,
                                item.bbox.x,
                                item.bbox.y,
                                item.bbox.width,
                                item.bbox.height
                        ));
                    }
                }
            } finally {
                // 防止内存泄露的关键代码：发生意外异常也要确保 C 内存被安全回收
                OcrCapi.INSTANCE.ocr_result_list_free(raw);
            }
            return resList;
        }

        @Override
        public void close() {
            if (handle != null) {
                // 销毁引擎
                OcrCapi.INSTANCE.ocr_engine_destroy(handle);
                handle = null;
            }
        }
    }

    // ==========================================
    // 4. 方向分类模型封装
    // ==========================================
    public static class OriModel implements AutoCloseable {
        private OcrCapi.OriModelHandle handle;

        /**
         * 初始化方向分类模型。
         *
         * @param modelPath 模型路径
         * @param config    配置；为 null 时使用默认配置
         */
        public OriModel(String modelPath, Config config) {
            handle = OcrCapi.INSTANCE.ocr_ori_model_create(modelPath, config != null ? config.toNative() : null);
            ensureHandle(handle, "创建方向分类模型");
        }

        /**
         * 对本地图片文件进行方向分类。
         *
         * @param filePath 图片绝对路径
         * @return 方向分类结果
         */
        public OrientationResult classifyFile(String filePath) {
            OcrCapi.OriResult.ByValue res = OcrCapi.INSTANCE.ocr_ori_model_classify_file(handle, filePath);
            return new OrientationResult(res.class_idx.intValue(), res.angle, res.confidence);
        }

        @Override
        public void close() {
            if (handle != null) {
                OcrCapi.INSTANCE.ocr_ori_model_destroy(handle);
                handle = null;
            }
        }
    }

    // ==========================================
    // 5. 统一错误拦截器
    // ==========================================

    /**
     * 校验原生句柄非空，否则读取原生错误信息并抛出 {@link OcrException}。
     *
     * @param handle    原生句柄（OcrEngineHandle 或 OriModelHandle）
     * @param operation 失败时的操作描述，用于组装异常信息
     */
    private static void ensureHandle(PointerType handle, String operation) {
        if (handle != null) {
            return;
        }
        Pointer errPtr = OcrCapi.INSTANCE.ocr_get_last_error();
        if (errPtr != null) {
            String msg = errPtr.getString(0, "UTF-8");
            OcrCapi.INSTANCE.ocr_free_string(errPtr); // 立即释放 C 分配的字符串内存
            throw new OcrException(operation + " 失败: " + msg);
        }
        throw new OcrException(operation + " 失败: 未知原生错误");
    }

    /**
     * 获取 PaddleOCR 库版本号。
     *
     * @return 版本字符串
     */
    public static String getVersion() {
        return OcrCapi.INSTANCE.ocr_version();
    }
}
