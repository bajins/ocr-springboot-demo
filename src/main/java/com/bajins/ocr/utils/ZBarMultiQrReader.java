package com.bajins.ocr.utils;

import io.github.doblon8.jzbar.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

public class ZBarMultiQrReader {

    public static void main(String[] args) throws Exception {
        // 1. 读取测试图片（换成你自己的标签图片路径）
        File imgFile = new File("labels.png");
        BufferedImage img = ImageIO.read(imgFile);

        // 2. 转为 Y800 灰度数据（jzbar 要求格式 "Y800"）
        byte[] y800Data = null;// ImageUtils.convertToY800(img);

        // 3. 创建 ImageScanner 和 Image
        /*try (ImageScanner scanner = new ImageScanner();
             Image image = new Image()) {

            // 4. 配置扫描器：同时支持常见一维码 + 二维码
            // 先禁用所有类型
            scanner.setConfig(SymbolType.NONE, Config.ENABLE, 0);

            // 按需启用你需要的一维码/二维码类型
            scanner.setConfig(SymbolType.EAN13, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.EAN8, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.UPCA, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.UPCE, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.CODE128, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.CODE39, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.QRCODE, Config.ENABLE, 1);
            scanner.setConfig(SymbolType.PDF417, Config.ENABLE, 1);
            // 还可以按需启用 I25 / ISBN10 / ISBN13 等

            // 5. 设置图像数据
            image.setSize(img.getWidth(), img.getHeight());
            image.setFormat("Y800");   // 必须是 Y800
            image.setData(y800Data);

            // 6. 执行扫描
            int symbolCount = scanner.scanImage(image);
            System.out.println("识别到符号数量: " + symbolCount);

            if (symbolCount == 0) {
                System.out.println("没有识别到任何条码");
                return;
            }

            // 7. 遍历所有识别到的条码
            List<Symbol> symbols = image.getSymbols(); // 推荐
            for (Symbol symbol : symbols) {
                int type = Integer.parseInt(symbol.getType());
                String data = symbol.getData();

                // 根据 type 区分一维码 / 二维码
                String category = categorizeBarcodeType(type);

                System.out.println("----------------------");
                System.out.println("类型 ID   : " + type);
                System.out.println("分类      : " + category);
                System.out.println("内容      : " + data);
                System.out.println("质量      : " + symbol.getQuality());
                System.out.println("位置多边形: " + symbol.getLocationPolygon());
            }

            // 也可以用传统方式：从 firstSymbol 不断 next()
            // Symbol sym = image.getFirstSymbol();
            // while (sym != null) {
            //     ...
            //     sym = sym.next();
            // }
        }*/
    }

    /**
     * 根据 SymbolType 常量，简单归类为一维码 / 二维码 / 其它
     */
    private static String categorizeBarcodeType(int type) {
        /*if (type == SymbolType.QRCODE || type == SymbolType.PDF417 || type == SymbolType.SQCODE) {
            return "二维码";
        } else if (type == SymbolType.EAN13 || type == SymbolType.EAN8
                || type == SymbolType.UPCA || type == SymbolType.UPCE
                || type == SymbolType.CODE128 || type == SymbolType.CODE39
                || type == SymbolType.CODE93 || type == SymbolType.CODABAR
                || type == SymbolType.I25 || type == SymbolType.ISBN10
                || type == SymbolType.ISBN13 || type == SymbolType.ADDON
                || type == SymbolType.ADDON2 || type == SymbolType.ADDON5) {
            return "一维码";
        } else {*/
            return "其它";
//        }
    }
}