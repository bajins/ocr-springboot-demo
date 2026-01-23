import com.bajins.ocr.utils.ImageCropAndRotate;
import net.sourceforge.tess4j.*;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.ResultIterator;
import org.bytedeco.tesseract.TessBaseAPI;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.bytedeco.leptonica.global.leptonica.*;
import static org.bytedeco.tesseract.global.tesseract.RIL_WORD;

public class TestTesseract {

    public static void main(String[] args) throws URISyntaxException {
        String path = "";
        try {
            URL url = Thread.currentThread().getContextClassLoader().getResource("images/17615436349877.png");
            if (url == null) {
                throw new IllegalArgumentException("未找到资源");
            }
            // 自动处理编码、特殊字符
            URI uri = url.toURI();
            path = ImageCropAndRotate.processImage(Paths.get(uri).toString());
        } catch (IOException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        /*
        https://github.com/bytedeco/javacpp-presets/tree/master/tesseract
         */
        // 1. 加载图像（Leptonica）
        PIX image = pixRead(path);
        if (image == null) {
            throw new RuntimeException("图像加载失败");
        }
        // 转灰度
        // PIX gray = pixConvertTo8(image, 0);
        // 2. 初始化 Tesseract
        TessBaseAPI api = new TessBaseAPI();
        String datapath = null;
        // 初始化前设置！
        // api.SetVariable("tessedit_ocr_engine_mode", "1"); // 1 = LSTM_ONLY
        // 设置 tessdata 路径 & 语言
        // https://github.com/tesseract-ocr/tessdata_best
        // https://github.com/tesseract-ocr/tessdata
        URL url = Thread.currentThread().getContextClassLoader().getResource("tessdata");
        // 检查协议：如果是 file 协议（开发环境），直接返回路径
        if (url != null && "file".equals(url.getProtocol())) {
            // 自动处理编码、特殊字符
            URI uri = url.toURI();
            Path relativeDir = Paths.get(uri);
            if (relativeDir.toFile().exists()) {
                datapath = relativeDir.toString(); // 含 chi_sim.traineddata 等
            }
        }
        if (datapath == null) {
            // 注意：classpath: 只能读取资源，不能写入；且打包后是只读的
            Resource resource = new FileSystemResourceLoader().getResource("classpath:tessdata");
            if (resource.exists()) {
                try {
                    datapath = resource.getFile().getAbsolutePath(); // 含 chi_sim.traineddata 等
                } catch (IOException e) {
                }
            }
        }
        if (datapath == null) {
            // 获取当前工作目录下指定目录的子目录路径（相对路径）
            Path relativeDir = Paths.get("model", "ocr", "tessdata");
            if (relativeDir.toFile().exists()) {
                datapath = relativeDir.toString(); // 含 chi_sim.traineddata 等
            }
        }
        if (datapath == null) {
            ApplicationHome home = new ApplicationHome(TestTesseract.class);
            File jarDir = home.getSource().getParentFile(); // jar 所在目录
            Path relativeDir = Paths.get(jarDir.getAbsolutePath(), "model", "ocr", "tessdata");
            if (relativeDir.toFile().exists()) {
                datapath = relativeDir.toString(); // 含 chi_sim.traineddata 等
            }
        }
        if (datapath == null) {
            throw new IllegalArgumentException("未找到资源");
        }
        int initResult = api.Init(datapath, "chi_sim+eng");
        if (initResult != 0) {
            throw new RuntimeException("Tesseract 初始化失败: " + initResult);
        }
        // 3. 设置图像
        api.SetImage(image);
        // api.SetPageSegMode(ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK); // 等价于 --psm 6

        // 4. 识别文本
        BytePointer outText = api.GetUTF8Text();
        System.out.println("识别结果：\n" + outText.getString());

        // 5. 清理
        api.End(); // 必须调用！
        outText.deallocate();
        pixDestroy(image);


        /*
         https://github.com/nguyenq/tess4j
         */
        ITesseract tesseract = new Tesseract();

        // 设置语言（默认 eng，可多语言：如 "chi_sim+eng"）
        tesseract.setLanguage("chi_sim+eng"); // 简体中文
        // tesseract.setPageSegMode(6);       // 单块文本
        // tesseract.setOcrEngineMode(1);     // LSTM 模式

        // 设置 tessdata 路径（若使用自定义训练数据）
        tesseract.setDatapath(datapath);

        try {
            // 识别图片
            String result = tesseract.doOCR(new File(path));
            System.out.println("识别结果：\n" + result.trim());
            // 内存图片
            // BufferedImage bi = ImageIO.read(new File("screenshot.png"));
            // String text = tesseract.doOCR(bi);

            // 获取识别结果详细信息（含坐标、置信度）
            /*List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            for (Word word : words) {
                System.out.printf("文本: %s, 置信度: %.2f, 位置: %s%n",
                        word.getText(), word.getConfidence(), word.getBoundingBox());
            }*/
        } catch (TesseractException e) {
            System.err.println("OCR 失败: " + e.getMessage());
        }
    }
}
