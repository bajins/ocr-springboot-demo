package com.bajins.ocr.utils;

import io.github.doblon8.jzbar.*;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.util.List;

public class ZBarMultiQrReader {

    public static void main(String[] args) throws Exception {
        // 1. 读取测试图片（换成你自己的标签图片路径）
        File imgFile = new File("F:\\workspace\\workspace-a\\2026-05-05_163050.png");
        BufferedImage img = ImageIO.read(imgFile);

        // 2. 转为 Y800 灰度数据（jzbar 要求格式 "Y800"，每像素 1 字节灰度）
        byte[] y800Data = convertToY800(img);

        // 3. 创建 ImageScanner 和 Image
        try (ImageScanner scanner = new ImageScanner();
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
                // jzbar Symbol.getType() 返回类型名称字符串(如 "QR-Code"),内部 int 已被丢弃且未对外暴露
                String typeName = symbol.getType();
                String data = symbol.getData();

                // 根据类型名称区分一维码 / 二维码
                String category = categorizeBarcodeType(typeName);

                System.out.println("----------------------");
                System.out.println("类型名称 : " + typeName);
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
        }
    }

    /**
     * 根据 jzbar Symbol.getType() 返回的类型名称字符串,归类为一维码 / 二维码 / 其它。
     * 名称对应 zbar_get_symbol_name 的标准输出(如 "QR-Code"、"EAN-13"、"CODE-128")。
     *
     * @param typeName 符号类型名称,来自 Symbol.getType(),可能为 null 或 "UNKNOWN"
     * @return 分类:二维码 / 一维码 / 其它 / 未知
     */
    private static String categorizeBarcodeType(String typeName) {
        if (typeName == null) {
            return "未知";
        }
        return switch (typeName) {
            // 二维码
            case "QR-Code", "SQ-Code", "PDF417" -> "二维码";
            // 一维码
            case "EAN-13", "EAN-8", "UPC-A", "UPC-E", "CODE-128", "CODE-39", "CODE-93", "CODABAR", "I2/5", "ISBN-10",
                 "ISBN-13", "ADDON", "ADDON2", "ADDON5" -> "一维码";
            default -> "其它";
        };
    }

    /**
     * 将图像转换为 Y800 单通道灰度字节数据(jzbar 要求的输入格式)。
     * <p>
     * Y800 每像素 1 字节(0~255),数据长度 = width × height。
     * 统一转为 8 位灰度缓冲图后取其 raster 原始字节,保证格式一致。
     *
     * @param img 原始图像,可为任意颜色类型
     * @return Y800 灰度字节数组,长度为 width × height
     */
    private static byte[] convertToY800(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        // 非灰度图先转为 8 位灰度,统一从 raster 取字节
        BufferedImage gray;
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            gray = img;
        } else {
            gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = gray.createGraphics();
            graphics.drawImage(img, 0, 0, null);
            graphics.dispose();
        }
        // DataBufferByte 的内部字节即 Y800 灰度值,jzbar.setData 会拷贝到堆外内存,直接返回即可
        return ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
    }
}