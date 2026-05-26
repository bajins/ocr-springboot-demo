import ai.djl.modality.cv.Image;
import ai.djl.util.JsonUtils;
import cn.smartjavaai.common.config.Config;
import cn.smartjavaai.common.cv.SmartImageFactory;
import cn.smartjavaai.common.enums.DeviceEnum;
import cn.smartjavaai.common.utils.ImageUtils;
import cn.smartjavaai.ocr.config.DirectionModelConfig;
import cn.smartjavaai.ocr.config.OcrDetModelConfig;
import cn.smartjavaai.ocr.config.OcrRecModelConfig;
import cn.smartjavaai.ocr.config.OcrRecOptions;
import cn.smartjavaai.ocr.entity.OcrInfo;
import cn.smartjavaai.ocr.enums.CommonDetModelEnum;
import cn.smartjavaai.ocr.enums.CommonRecModelEnum;
import cn.smartjavaai.ocr.enums.DirectionModelEnum;
import cn.smartjavaai.ocr.factory.OcrModelFactory;
import cn.smartjavaai.ocr.model.common.detect.OcrCommonDetModel;
import cn.smartjavaai.ocr.model.common.direction.OcrDirectionModel;
import cn.smartjavaai.ocr.model.common.recognize.OcrCommonRecModel;
import com.bajins.ocr.utils.ImageCropAndRotate;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
public class TestSmartJavaAI {
    // 设备类型
    public static DeviceEnum device = DeviceEnum.CPU;

    static {
        SmartImageFactory.setEngine(SmartImageFactory.Engine.OPENCV);
        // 修改缓存路径
        // Config.setCachePath("/Users/xxx/smartjavaai_cache");
        // System.setProperty("ai.djl.default_engine", "PyTorch");
        log.info("默认缓存地址：{}", Config.getCachePath());
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
        try {
            OcrInfo ocrInfo = recognize2(getProRecModel(), path);
            log.info("OCR识别结果：{}\nfullText：{}", JsonUtils.toJson(ocrInfo), ocrInfo.getFullText());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取通用识别模型（高精确度模型）
     * 注意事项：高精度模型，识别准确度高，速度慢
     * @return
     */
    public static OcrCommonRecModel getProRecModel() {
        OcrRecModelConfig recModelConfig = new OcrRecModelConfig();
        // 指定文本识别模型，切换模型需要同时修改modelEnum及modelPath
        recModelConfig.setRecModelEnum(CommonRecModelEnum.PP_OCR_V5_SERVER_REC_MODEL);
        // 指定识别模型位置，需要更改为自己的模型路径（下载地址请查看文档）
        recModelConfig.setRecModelPath("model/ocr/PaddleOCR/PP-OCRv5_server_rec.onnx");
        recModelConfig.setDevice(device);
        recModelConfig.setTextDetModel(getProDetectionModel());
        recModelConfig.setDirectionModel(getDirectionModel());
        return OcrModelFactory.getInstance().getRecModel(recModelConfig);
    }

    /**
     * 获取通用识别模型（极速模型）
     * 注意事项：极速模型，识别准确度低，速度快
     * @return
     */
    public OcrCommonRecModel getFastRecModel() {
        OcrRecModelConfig recModelConfig = new OcrRecModelConfig();
        // 指定文本识别模型，切换模型需要同时修改modelEnum及modelPath
        recModelConfig.setRecModelEnum(CommonRecModelEnum.PP_OCR_V5_MOBILE_REC_MODEL);
        // 指定识别模型位置，需要更改为自己的模型路径（下载地址请查看文档）
        recModelConfig.setRecModelPath("model/ocr/PaddleOCR/PP-OCRv5_mobile_rec_infer.onnx");
        recModelConfig.setDevice(device);
        recModelConfig.setTextDetModel(getFastDetectionModel());
        return OcrModelFactory.getInstance().getRecModel(recModelConfig);
    }


    /**
     * 获取文本检测模型(极速模型)
     * 注意事项：极速模型，识别准确度低，速度快
     * @return
     */
    public static OcrCommonDetModel getFastDetectionModel() {
        OcrDetModelConfig config = new OcrDetModelConfig();
        // 指定检测模型，切换模型需要同时修改modelEnum及modelPath
        config.setModelEnum(CommonDetModelEnum.PP_OCR_V5_MOBILE_DET_MODEL);
        // 指定模型位置，需要更改为自己的模型路径（下载地址请查看文档）
        config.setDetModelPath("model/ocr/PaddleOCR/PP-OCRv5_mobile_det_infer.onnx");
        config.setDevice(device);
        return OcrModelFactory.getInstance().getDetModel(config);
    }

    /**
     * 获取文本检测模型(高精确度模型)
     * 注意事项：高精度模型，识别准确度高，速度慢
     * @return
     */
    public static OcrCommonDetModel getProDetectionModel() {
        OcrDetModelConfig config = new OcrDetModelConfig();
        // 指定检测模型，切换模型需要同时修改modelEnum及modelPath
        config.setModelEnum(CommonDetModelEnum.PP_OCR_V5_SERVER_DET_MODEL);
        // 指定模型位置，需要更改为自己的模型路径（下载地址请查看文档）
        config.setDetModelPath("model/ocr/PaddleOCR/PP-OCRv5_server_det.onnx");
        config.setDevice(device);
        return OcrModelFactory.getInstance().getDetModel(config);
    }

    /**
     * 获取方向检测模型
     * @return
     */
    public static OcrDirectionModel getDirectionModel() {
        DirectionModelConfig directionModelConfig = new DirectionModelConfig();
        // 指定行文本方向检测模型，切换模型需要同时修改modelEnum及modelPath
        directionModelConfig.setModelEnum(DirectionModelEnum.PP_LCNET_X0_25);
        // 指定行文本方向检测模型路径，需要更改为自己的模型路径（下载地址请查看文档）
        directionModelConfig.setModelPath("model/ocr/PaddleOCR/PP-LCNet_x0_25_textline_ori_infer.onnx");
        directionModelConfig.setDevice(device);
        return OcrModelFactory.getInstance().getDirectionModel(directionModelConfig);
    }


    /**
     * 文本识别
     * 支持简体中文、繁体中文、英文、日文四种主要语言，以及手写、竖版、拼音、生僻字
     * 流程：文本检测 -> 文本识别
     * 注意事项：
     * 1、批量检测时，模型应统一放在外层 try 中使用，避免重复加载，自动释放资源更安全。
     * 2、模型文件需要放在单独文件夹
     * 3、路径不能有中文
     *
     * @return
     */
    public static OcrInfo recognize(OcrCommonRecModel recModel, String filePath) throws IOException {
        // 不带方向矫正，分行返回文本
        OcrRecOptions options = new OcrRecOptions(false, true);
        // 创建Image对象，可以从文件、url、InputStream创建、BufferedImage、Base64创建，具体使用方法可以查看文档
        Image image = SmartImageFactory.getInstance().fromFile(filePath);
        OcrInfo ocrInfo = recModel.recognize(image, options);
        return ocrInfo;
    }

    /**
     * 文本识别（带方向矫正）
     * 支持简体中文、繁体中文、英文、日文四种主要语言，以及手写、竖版、拼音、生僻字
     * 本方法支持多角度文字识别
     * 流程：文本检测 -> 方向检测 -> 方向矫正 ->  文本识别
     * 注意事项：
     * 1、批量检测时，模型应统一放在外层 try 中使用，避免重复加载，自动释放资源更安全。
     * 2、模型文件需要放在单独文件夹
     * 3、路径不能有中文
     *
     * @return
     */
    public static OcrInfo recognize2(OcrCommonRecModel recModel, String filePath) throws IOException {
        // 带方向矫正，分行返回文本
        OcrRecOptions options = new OcrRecOptions(true, true);
        // 创建Image对象，可以从文件、url、InputStream创建、BufferedImage、Base64创建，具体使用方法可以查看文档
        Image image = SmartImageFactory.getInstance().fromFile(filePath);
        OcrInfo ocrInfo = recModel.recognize(image, options);
        return ocrInfo;
    }

    /**
     * 文本识别（手写字）
     * 支持简体中文、繁体中文、英文、日文四种主要语言，以及手写、竖版、拼音、生僻字
     * 流程：文本检测 -> 文本识别
     * 注意事项：
     * 1、批量检测时，模型应统一放在外层 try 中使用，避免重复加载，自动释放资源更安全。
     * 2、模型文件需要放在单独文件夹
     *
     * @return
     */
    public static OcrInfo recognizeHandWriting(OcrCommonRecModel recModel) throws IOException {
        // 创建Image对象，可以从文件、url、InputStream创建、BufferedImage、Base64创建，具体使用方法可以查看文档
        Image image = SmartImageFactory.getInstance().fromFile("src/main/resources/handwriting_1.jpg");
        OcrInfo ocrInfo = recModel.recognize(image, new OcrRecOptions());
        return ocrInfo;
    }

    /**
     * 文本识别并绘制结果
     * 支持简体中文、繁体中文、英文、日文四种主要语言，以及手写、竖版、拼音、生僻字
     * 流程：文本检测 -> 文本识别
     * 注意事项：
     * 1、批量检测时，模型应统一放在外层 try 中使用，避免重复加载，自动释放资源更安全。
     * 2、模型文件需要放在单独文件夹
     */
    public static void recognizeAndDraw(OcrCommonRecModel recModel) {
        int fontSize = 18;
        recModel.recognizeAndDraw("src/main/resources/general_ocr_002.png", "output/ocr_4_recognized.jpg", fontSize, new OcrRecOptions());
    }

    public static void recognizeAndDraw2(OcrCommonRecModel recModel) throws IOException {
        int fontSize = 18;
        // 创建保存路径
        Path inputImagePath = Paths.get("src/main/resources/general_ocr_002.png");
        Path imageOutputPath = Paths.get("output/ocr_5_recognized.jpg");
        // 创建Image对象，可以从文件、url、InputStream创建、BufferedImage、Base64创建，具体使用方法可以查看文档
        Image image = SmartImageFactory.getInstance().fromFile(inputImagePath);
        OcrInfo ocrInfo = recModel.recognizeAndDraw(image, fontSize, new OcrRecOptions());
        log.info("OCR识别结果：{}", JsonUtils.toJson(ocrInfo));
        // 保存绘制结果
        if (ocrInfo != null && ocrInfo.getDrawnImage() != null) {
            ImageUtils.save(ocrInfo.getDrawnImage(), imageOutputPath.toAbsolutePath().toString());
        }
        // recModel.recognizeAndDraw(inputImagePath.toString(), imageOutputPath.toString(), fontSize, new OcrRecOptions());
    }


    /**
     * 批量识别
     * 注意事项：
     * 1、批量检测时，模型应统一放在外层 try 中使用，避免重复加载，自动释放资源更安全。
     * 2、模型文件需要放在单独文件夹
     *
     * @return
     */
    public static List<OcrInfo> batchRecognize(OcrCommonRecModel recModel, String folderPath) throws IOException {
        // 批量检测要求图片宽高一致
        // 读取文件夹中所有图片
        List<Image> images = ImageUtils.readImagesFromFolder(folderPath);
        // 带方向矫正，分行返回文本
        OcrRecOptions options = new OcrRecOptions(true, true);
        List<OcrInfo> ocrResult = recModel.batchRecognizeDJLImage(images, options);
        /*for (int i = 0; i < ocrResult.size(); i++) {
            log.info("图片" + i + "文本识别结果：{}", JsonUtils.toJson(ocrResult.get(i)));
        }*/
        return ocrResult;
    }
}
