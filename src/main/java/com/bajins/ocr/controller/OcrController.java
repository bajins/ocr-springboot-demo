package com.bajins.ocr.controller;

import com.bajins.ocr.utils.ImageCropAndRotate;
import com.benjaminwan.ocrlibrary.OcrResult;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.ParamConfig;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * OCR识别控制器
 * @author bajins.com
 */
@RestController
@RequestMapping("/ocr")
public class OcrController {

    // ICCID码正则表达式：14位+，包含大写字母（除了I、O、Q）和数字
    private static final Pattern ICCID_PATTERN = Pattern.compile("^[0-9A-HJ-NPR-Z]{14,}$");

    // private static final ParamConfig PARAM_CONFIG = new ParamConfig(50, 0, 0.5F, 0.3F, 1.6F, true, true);
    private static final ParamConfig PARAM_CONFIG = ParamConfig.getDefaultConfig();
    private static final InferenceEngine INFERENCE_ENGINE = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);

    static {
        // INFERENCE_ENGINE.initLogger(true, true, true);
        PARAM_CONFIG.setDoAngle(true);
        PARAM_CONFIG.setMostAngle(true);
    }

    @GetMapping()
    public String ocr() {

        String path;
        try {
            URL url = Thread.currentThread().getContextClassLoader().getResource("images/17615436349877.png");
            if (url == null) {
                throw new IllegalArgumentException("未找到资源");
            }
            // 自动处理编码、特殊字符
            URI uri = url.toURI();
            path = ImageCropAndRotate.processImage(Paths.get(uri).toString());
        } catch (IOException e) {
            return e.getMessage();
        } catch (URISyntaxException e) {
            return e.getMessage();
        }
        // 开始识别
        OcrResult ocrResult = INFERENCE_ENGINE.runOcr(path, PARAM_CONFIG);

        Matcher matcher = ICCID_PATTERN.matcher(ocrResult.getStrRes().replaceAll("\\s+", ""));
        if (matcher.find()) {
            return matcher.group();
        }
        return "未识别到ICCID码，文字读取结果：" + ocrResult.getStrRes();
    }

    @PostMapping
    public String ocr(@RequestParam("file") MultipartFile fileUpload) throws IOException {
        if (fileUpload.isEmpty()) {
            return "请选择一个文件上传";
        }
        File baseDir = Paths.get(System.getProperty("java.io.tmpdir"), "ocrJava").toFile();
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        String filename = fileUpload.getOriginalFilename();
        File targetFile = new File(baseDir, filename).getCanonicalFile();
        if (!targetFile.getPath().startsWith(baseDir.getCanonicalPath())) {
            throw new SecurityException("Invalid file path");
        }
        fileUpload.transferTo(targetFile);
        targetFile.deleteOnExit();
        // Files.copy(fileUpload.getInputStream(), targetFile.toPath());

        String path;
        try {
            path = ImageCropAndRotate.processImage(targetFile.getAbsolutePath());
        } catch (IOException e) {
            return e.getMessage();
        }

        // 开始识别
        OcrResult ocrResult = INFERENCE_ENGINE.runOcr(path, PARAM_CONFIG);

        Matcher matcher = ICCID_PATTERN.matcher(ocrResult.getStrRes().replaceAll("\\s+", ""));
        if (matcher.find()) {
            return matcher.group();
        }
        return "未识别到ICCID码，文字读取结果：" + ocrResult.getStrRes();
    }
}
