package com.bajins.ocr.utils;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class ZXingMultiQrReader {

    public static List<String> decodeQrCodes(File imageFile) throws Exception {
        BufferedImage img = ImageIO.read(imageFile);
        // scale=1 不放大;若图过于密集可调大,但像素量按 scale² 增长、耗时显著上升
        final int scale = 1;
        BufferedImage base = img;
        if (scale > 1) {
            base = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D scaler = base.createGraphics();
            scaler.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            scaler.drawImage(img, 0, 0, base.getWidth(), base.getHeight(), null);
            scaler.dispose();
        }

        // 转单通道灰度(ZXing 内部再二值化,这里统一通道格式)
        BufferedImage gray = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.drawImage(base, 0, 0, null);
        gGray.dispose();

        LuminanceSource source = new BufferedImageLuminanceSource(gray);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        // GenericMultipleBarcodeReader 自身按已找到码的边界递归切分,支持一维码+二维码多码;
        // 不再用 ByQuadrantReader(其十字切四象限会切断横向一维码)
        MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(new MultiFormatReader());

        // hints:保留 TRY_HARDER 提升识别率;标签为黑码白底,去掉 ALSO_INVERTED(反色探测耗时翻倍)
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // 限定支持的格式,避免 MultiFormatReader 盲试全部 ~15 种格式拖慢速度
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.ITF,
                BarcodeFormat.CODABAR,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E
        ));

        // 一次性解码所有码
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
        System.out.println("识别到 " + qrTexts.size() + " 个条码：");
        for (String text : qrTexts) {
            System.out.println(text);
        }
    }
}
