package com.bajins.ocr.utils.rustpaddleocr;

/**
 * PaddleOCR 原生调用统一异常（业务可预期失败）。
 * <p>
 * 覆盖引擎创建失败、识别参数非法、原生返回错误等可预期场景；
 * 不可预期的运行时故障（如 JNA 链接错误）保留为系统异常，不由本类承载。
 */
public class OcrException extends RuntimeException {

    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
