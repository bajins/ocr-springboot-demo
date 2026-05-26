package com.bajins.ocr.utils;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.ByQuadrantReader;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ZXingMultiQrReader {

    public static List<String> decodeQrCodes(File imageFile) throws Exception {
        BufferedImage img = ImageIO.read(imageFile);
        // 适当放大（太密集时很有用）
        int scale = 2;
        BufferedImage scaled = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        g.dispose();
        // 灰度 + 简单增强
        BufferedImage gray = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.drawImage(scaled, 0, 0, null);
        gGray.dispose();

        LuminanceSource source = new BufferedImageLuminanceSource(gray);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        // 1. 基础 Reader（支持多种条码/二维码，包括 QR）
        Reader baseReader = new MultiFormatReader();

        // 2. 如果场景是“一张图里多个 QR 码”，建议用 ByQuadrantReader 包装一下
        //    官方建议：多个 2D 码时用 ByQuadrantReader，能提高检测率
        Reader quadrantReader = new ByQuadrantReader(baseReader);

        // 3. 再包装成多码 Reader
        MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(quadrantReader);

        // 4. 设置 hints（TRY_HARDER 对多码场景很有帮助）
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // 如果明确只关心 QR
//        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        // 如果图是白底黑码，可以试试反色
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

        // 5. 一次性解码所有码
        Result[] results = multiReader.decodeMultiple(bitmap, hints);

        List<String> texts = new ArrayList<>();
        for (Result result : results) {
            texts.add(result.getText());
        }
        return texts;
    }

    public static void main(String[] args) throws Exception {
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
        File file = new File(path); // 你的标签图片
        List<String> qrTexts = decodeQrCodes(file);
        System.out.println("识别到 " + qrTexts.size() + " 个二维码：");
        for (String text : qrTexts) {
            System.out.println(text);
        }
    }
}