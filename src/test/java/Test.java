import cn.smartjavaai.ocr.entity.OcrInfo;
import com.benjaminwan.ocrlibrary.OcrResult;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.ParamConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.regex.Matcher;

public class Test {
    // private static final ParamConfig PARAM_CONFIG = new ParamConfig(50, 0, 0.5F, 0.3F, 1.6F, true, true);
    private static final ParamConfig PARAM_CONFIG = ParamConfig.getDefaultConfig();
    private static final InferenceEngine INFERENCE_ENGINE = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);

    static {
        // INFERENCE_ENGINE.initLogger(true, true, true);
        PARAM_CONFIG.setDoAngle(true);
        PARAM_CONFIG.setMostAngle(true);
    }

    public static void main(String[] args) {
        // 根据文件夹获取文件夹下所有图片文件
        String path = "D:\\wechat\\url";
        File[] files = new File(path).listFiles();
        if (files != null) {
            // 使用 try-with-resources 自动关闭 FileWriter
            try (FileWriter writer = new FileWriter(Paths.get(path, "2.txt").toString(), true)) { // true 表示追加模式
                String regex = "[\\n\\r]+|(GitHub|github)[:：]( |)|地址：|https://|/( |)$| ";
                for (File file : files) {
                    String fileName = file.getName();
                    if (fileName.endsWith(".png") || fileName.endsWith(".jpg")) {
                        // 开始识别
                        OcrResult ocrResult = INFERENCE_ENGINE.runOcr(file.getAbsolutePath(), PARAM_CONFIG);
                        String fullText = ocrResult.getStrRes().replaceAll(regex, "");
                        System.out.println(fullText);

                        /*OcrInfo ocrInfo = TestSmartJavaAI.recognize2(TestSmartJavaAI.getProRecModel(), file.getAbsolutePath());
                        String fullText = ocrInfo.getFullText().replaceAll(regex, "");
                        System.out.println(fullText);*/

                        writer.write(fullText + "\n");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
