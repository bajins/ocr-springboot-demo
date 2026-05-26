package com.bajins.ocr.utils.rustpaddleocr;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * https://github.com/zibo-chen/paddle-ocr-capi
 * https://github.com/zibo-chen/rust-paddle-ocr
 */
public class PaddleOcrEngine {
    private OcrCapi.OcrEngineHandle handle;

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
    // 2. 优雅的参数配置器
    // ==========================================
    public static class Config {
        private Backend backend = Backend.CPU;
        private int threadCount = 4;
        private Precision precision = Precision.NORMAL;
        private int detMaxSideLen = 960;
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

    // 自定义的、脱离JNA依赖的结果类
    public static class Result {
        public String text;
        public float confidence;
        public int x, y, width, height;

        @Override
        public String toString() {
            return String.format("文本: %s, 置信度: %.2f%%, 坐标: (%d,%d,%d,%d)",
                    text, confidence * 100, x, y, width, height);
        }
    }

    public static class OrientationResult {
        public int classIdx;
        public int angle;
        public float confidence;
    }


    // ==========================================
    // 4. OCR 引擎封装 (核心)
    // ==========================================
    public static class Engine implements AutoCloseable {
        private OcrCapi.OcrEngineHandle handle;

        /**
         * 初始化 OCR 引擎
         */
        public Engine(String detPath, String recPath, String keysPath, Config config) {
            handle = OcrCapi.INSTANCE.ocr_engine_create(
                    detPath, recPath, keysPath, config != null ? config.toNative() : null
            );
            checkAndRaiseError(handle == null);
        }

        public Engine(String detPath, String recPath, String keysPath, String oriPath, Config config) {
            handle = OcrCapi.INSTANCE.ocr_engine_create_with_ori(
                    detPath, recPath, keysPath, oriPath, config != null ? config.toNative() : null
            );
            checkAndRaiseError(handle == null);
        }

        /**
         * 识别本地图片文件
         */
        public List<Result> recognizeFile(String filePath) {
            OcrCapi.OcrResultList.ByValue raw = OcrCapi.INSTANCE.ocr_engine_recognize_file(handle, filePath);
            return extractAndFree(raw);
        }

        /**
         * 零拷贝识别 RGB 图像数据
         *
         * @param directBuffer 必须是 ByteBuffer.allocateDirect(size) 分配的 ByteBuffer 或者是从 Native 传递过来的
         */
        public List<Result> recognizeRgb(ByteBuffer directBuffer, int width, int height) {
            if (!directBuffer.isDirect()) {
                throw new IllegalArgumentException("For zero-copy performance, ByteBuffer must be Direct!");
            }
            OcrCapi.OcrResultList.ByValue raw = OcrCapi.INSTANCE.ocr_engine_recognize_rgb(handle, directBuffer, width, height);
            return extractAndFree(raw);
        }

        /**
         * 内部处理结果列表并释放底层内存
         */
        private List<Result> extractAndFree(OcrCapi.OcrResultList.ByValue raw) {
            List<Result> resList = new ArrayList<>();
            try {
                int count = raw.count != null ? raw.count.intValue() : 0;
                if (count > 0 && raw.items != null) {
                    OcrCapi.OcrResult itemRef = new OcrCapi.OcrResult(raw.items);
                    OcrCapi.OcrResult[] items = (OcrCapi.OcrResult[]) itemRef.toArray(raw.count.intValue());

                    for (OcrCapi.OcrResult item : items) {
                        Result r = new Result();
                        r.text = item.text != null ? item.text.getString(0, "UTF-8") : "";
                        r.confidence = item.confidence;
                        r.x = item.bbox.x;
                        r.y = item.bbox.y;
                        r.width = item.bbox.width;
                        r.height = item.bbox.height;
                        resList.add(r);
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
    // 方向分类模型封装
    // ==========================================
    public static class OriModel implements AutoCloseable {
        private OcrCapi.OriModelHandle handle;

        public OriModel(String modelPath, Config config) {
            handle = OcrCapi.INSTANCE.ocr_ori_model_create(modelPath, config != null ? config.toNative() : null);
            checkAndRaiseError(handle == null);
        }

        public OrientationResult classifyFile(String filePath) {
            OcrCapi.OriResult.ByValue res = OcrCapi.INSTANCE.ocr_ori_model_classify_file(handle, filePath);
            OrientationResult r = new OrientationResult();
            r.classIdx = res.class_idx.intValue();
            r.angle = res.angle;
            r.confidence = res.confidence;
            return r;
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
    //  统一错误拦截器
    // ==========================================
    private static void checkAndRaiseError(boolean triggerCondition) {
        if (triggerCondition) {
            Pointer errPtr = OcrCapi.INSTANCE.ocr_get_last_error();
            if (errPtr != null) {
                String msg = errPtr.getString(0, "UTF-8");
                OcrCapi.INSTANCE.ocr_free_string(errPtr); // 立即释放 C 分配的字符串内存
                throw new RuntimeException("PaddleOCR Native Error: " + msg);
            }
            throw new RuntimeException("Unknown Native Pipeline Failure");
        }
    }

    // 版本号获取
    public static String getVersion() {
        return OcrCapi.INSTANCE.ocr_version();
    }
}