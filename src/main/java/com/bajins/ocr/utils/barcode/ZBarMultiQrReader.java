package com.bajins.ocr.utils.barcode;

import io.github.doblon8.jzbar.*;
import io.github.doblon8.jzbar.Image;
import io.github.doblon8.jzbar.Point;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * 基于 jzbar(io.github.doblon8:jzbar) 的多条码识别工具类，支持一维码与二维码同图识别。
 *
 * <p>核心设计：
 * <ul>
 *   <li>静态工具类：jzbar 的 ImageScanner/Image 每次扫描即建即释，无持久句柄与模型加载，
 *       故采用无状态静态方法，天然线程安全，对齐同目录 BoofCvMultiQrReader 风格。</li>
 *   <li>native 解耦：识别结果在 Image 释放前立即拷贝到 {@link Barcode} record，
 *       避免调用方持有依赖 Image 生命周期的 native Symbol 引用而导致数据失效。</li>
 *   <li>配置隔离：{@link Options} 持有启用的条码类型码集合(不可变)，提供 defaultConfig/qrOnly/linearOnly
 *       预设与 of/plus 自定义能力；类型码取自 jzbar SymbolType 的 int 常量(SymbolType 为常量类而非枚举)。
 *       命名为 Options 而非 Config 以避让 jzbar 的 Config 类(规范禁全限定名引用)。</li>
 *   <li>输入格式：jzbar 要求 Y800 单通道灰度，内部统一转 8 位灰度后取 raster 原始字节。</li>
 * </ul>
 *
 * <p>异常体系：
 * <ul>
 *   <li>参数非法(空图像/空配置/不存在文件)抛 IllegalArgumentException。</li>
 *   <li>图像读取失败或 jzbar 扫描失败(ZBarException)抛 IllegalStateException(包装受检异常)。</li>
 *   <li>jzbar 原生库缺失抛 UnsatisfiedLinkError(系统异常，不封装)，需确保运行环境含对应平台库。</li>
 * </ul>
 *
 * <p>依赖：io.github.doblon8:jzbar:0.4.0
 * <p>参考：https://github.com/doblon8/jzbar
 */
public class ZBarMultiQrReader {

    /**
     * 默认启用的条码类型码：常用一维码 + 二维码(取自 SymbolType int 常量)
     */
    private static final Set<Integer> DEFAULT_TYPES = Set.of(
            SymbolType.EAN13, SymbolType.EAN8, SymbolType.UPCA, SymbolType.UPCE,
            SymbolType.CODE128, SymbolType.CODE39, SymbolType.QRCODE, SymbolType.PDF417);

    /**
     * 默认配置单例(不可变，安全共享)
     */
    private static final Options DEFAULT_OPTIONS = new Options(DEFAULT_TYPES);


    /**
     * 按文件路径识别图片中的所有条码(默认配置)。
     * <p>等价于 {@link #detect(File) detect(new File(path))}：将路径包装为文件后读取并识别。
     *
     * @param path 图片文件路径(不可为 null)
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException 路径指向的文件不存在或不是文件
     * @throws IllegalStateException    读取失败或 jzbar 扫描失败
     */
    public static List<Barcode> detect(String path) {
        BufferedImage image = readImage(new File(path));
        return detect(image);
    }

    /**
     * 识别图像中的所有条码(默认配置：常用一维码 + 二维码)。
     *
     * @param image 源图像，可为彩色或灰度，内部统一转 Y800 灰度
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException image 为 null
     * @throws IllegalStateException    jzbar 扫描失败
     */
    public static List<Barcode> detect(BufferedImage image) {
        return detect(image, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图像中的所有条码。
     *
     * @param image   源图像，可为彩色或灰度
     * @param options 识别配置，决定启用哪些条码类型
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException image 或 options 为 null
     * @throws IllegalStateException    jzbar 扫描失败
     */
    public static List<Barcode> detect(BufferedImage image, Options options) {
        Objects.requireNonNull(image, "待识别图像不能为空");
        Objects.requireNonNull(options, "识别配置不能为空");
        return scan(image, options);
    }

    /**
     * 识别图片文件中的所有条码(默认配置)。
     *
     * @param file 图片文件，须存在且可读
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或 jzbar 扫描失败
     */
    public static List<Barcode> detect(File file) {
        return detect(file, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图片文件中的所有条码。
     *
     * @param file    图片文件，须存在且可读
     * @param options 识别配置
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalArgumentException file 为 null 或不存在，或 options 为 null
     * @throws IllegalStateException    读取失败或 jzbar 扫描失败
     */
    public static List<Barcode> detect(File file, Options options) {
        return detect(readImage(file), options);
    }

    /**
     * 识别图像中所有条码的文本内容(默认配置)。
     *
     * @param image 源图像
     * @return 条码文本列表，顺序与 {@link #detect(BufferedImage)} 一致
     * @throws IllegalArgumentException image 为 null
     * @throws IllegalStateException    jzbar 扫描失败
     */
    public static List<String> decode(BufferedImage image) {
        return decode(image, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图像中所有条码的文本内容。
     *
     * @param image   源图像
     * @param options 识别配置
     * @return 条码文本列表，顺序与 {@link #detect(BufferedImage, Options)} 一致
     * @throws IllegalArgumentException image 或 options 为 null
     * @throws IllegalStateException    jzbar 扫描失败
     */
    public static List<String> decode(BufferedImage image, Options options) {
        List<String> messages = new ArrayList<>();
        for (Barcode barcode : detect(image, options)) {
            messages.add(barcode.data());
        }
        return messages;
    }

    /**
     * 识别图片文件中所有条码的文本内容(默认配置)。
     *
     * @param file 图片文件
     * @return 条码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或 jzbar 扫描失败
     */
    public static List<String> decode(File file) {
        return decode(file, DEFAULT_OPTIONS);
    }

    /**
     * 按指定配置识别图片文件中所有条码的文本内容。
     *
     * @param file    图片文件
     * @param options 识别配置
     * @return 条码文本列表
     * @throws IllegalArgumentException file 为 null 或不存在，或 options 为 null
     * @throws IllegalStateException    读取失败或 jzbar 扫描失败
     */
    public static List<String> decode(File file, Options options) {
        return decode(readImage(file), options);
    }

    /**
     * 执行一次完整扫描：转灰度 -> 配置扫描器 -> 喂数据 -> 收集结果。
     * <p>结果在 Image 释放前完成拷贝，返回纯 Java 数据，与 native 生命周期解耦。
     *
     * @param image   源图像，已非空校验
     * @param options 识别配置，已非空校验
     * @return 识别到的条码列表(可能为空，但不为 null)
     * @throws IllegalStateException jzbar 扫描抛出 ZBarException 时包装抛出
     */
    private static List<Barcode> scan(BufferedImage image, Options options) {
        // jzbar 要求 Y800 单通道灰度，每像素 1 字节
        byte[] y800Data = convertToY800(image);
        try (ImageScanner scanner = new ImageScanner();
             Image zImage = new Image()) {
            configureScanner(scanner, options.enabledTypes());

            zImage.setSize(image.getWidth(), image.getHeight());
            zImage.setFormat("Y800");
            zImage.setData(y800Data);

            int symbolCount = scanner.scanImage(zImage);
            if (symbolCount == 0) {
                return List.of();
            }
            // 在 Image 释放前立即把 Symbol 字段拷贝到 record，避免调用方拿到失效的 native 引用
            List<Barcode> results = new ArrayList<>();
            for (Symbol symbol : zImage.getSymbols()) {
                String typeName = symbol.getType();
                results.add(new Barcode(
                        typeName,
                        categorizeBarcodeType(typeName),
                        symbol.getData(),
                        symbol.getQuality(),
                        symbol.getLocationPolygon()));
            }
            return results;
        } catch (ZBarException e) {
            // ZBarException 为受检异常，统一包装为 IllegalStateException 对外暴露
            throw new IllegalStateException("jzbar 扫描失败: " + e.getMessage(), e);
        }
    }

    /**
     * 配置扫描器启用的条码类型：先禁用全部，再按集合逐个启用。
     *
     * @param scanner      jzbar 图像扫描器，由调用方创建并负责释放
     * @param enabledTypes 需启用的条码类型码集合(取自 SymbolType 常量)
     * @throws ZBarException jzbar 原生配置失败
     */
    private static void configureScanner(ImageScanner scanner, Set<Integer> enabledTypes) throws ZBarException {
        // 先禁用所有类型，避免遗留启用项干扰
        scanner.setConfig(SymbolType.NONE, Config.ENABLE, 0);
        for (int type : enabledTypes) {
            scanner.setConfig(type, Config.ENABLE, 1);
        }
    }

    /**
     * 读取图片文件为 BufferedImage。
     *
     * @param file 图片文件，须存在且可读
     * @return 解码后的图像
     * @throws IllegalArgumentException file 为 null 或不存在
     * @throws IllegalStateException    读取失败或格式不支持(包装受检 IOException)
     */
    private static BufferedImage readImage(File file) {
        Objects.requireNonNull(file, "图片文件不能为空");
        if (!file.isFile()) {
            throw new IllegalArgumentException("图片文件不存在或不是文件: " + file);
        }
        try {
            BufferedImage image = ImageIO.read(file);
            // ImageIO.read 对不支持格式返回 null，需显式判空
            if (image == null) {
                throw new IllegalStateException("无法解码图片(格式不支持或文件损坏): " + file);
            }
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("读取图片失败: " + file, e);
        }
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
     * 单个条码识别结果(不可变)，与 native Symbol 生命周期解耦。
     *
     * @param typeName        jzbar 类型名称(如 "QR-Code"、"EAN-13")
     * @param category        按类型归类:二维码 / 一维码 / 其它 / 未知
     * @param data            条码内容文本
     * @param quality         识别质量分
     * @param locationPolygon 位置多边形顶点列表，每点含 x()/y() 坐标(可能为空)
     */
    public record Barcode(String typeName, String category, String data, int quality, List<Point> locationPolygon) {
    }

    /**
     * 识别配置(不可变)，持有启用的条码类型码集合。
     * <p>命名为 Options 而非 Config，以避让 jzbar 的 {@link Config} 类(规范禁止全限定名引用)。
     * 类型码取自 {@link SymbolType} 的 int 常量(SymbolType 为常量类而非枚举)。
     */
    public static final class Options {

        private final Set<Integer> enabledTypes;

        private Options(Set<Integer> enabledTypes) {
            this.enabledTypes = Set.copyOf(enabledTypes);
        }

        /**
         * 默认配置：启用常用一维码 + 二维码。
         *
         * @return 默认配置
         */
        public static Options defaultConfig() {
            return DEFAULT_OPTIONS;
        }

        /**
         * 仅二维码配置：QR-Code + PDF417。
         *
         * @return 仅二维码配置
         */
        public static Options qrOnly() {
            return new Options(Set.of(SymbolType.QRCODE, SymbolType.PDF417));
        }

        /**
         * 仅一维码配置：EAN/UPC/CODE128/CODE39。
         *
         * @return 仅一维码配置
         */
        public static Options linearOnly() {
            return new Options(Set.of(
                    SymbolType.EAN13, SymbolType.EAN8, SymbolType.UPCA, SymbolType.UPCE,
                    SymbolType.CODE128, SymbolType.CODE39));
        }

        /**
         * 自定义启用类型集合。
         *
         * @param types 需启用的条码类型码，至少一个，传 SymbolType.XXX 常量；重复会被去重
         * @return 新配置
         * @throws IllegalArgumentException types 为 null 或空
         */
        public static Options of(int... types) {
            if (types == null || types.length == 0) {
                throw new IllegalArgumentException("启用的条码类型不能为空");
            }
            // 逐个装箱加入 LinkedHashSet，容忍重复传入，构造时再 Set.copyOf 去重并转不可变
            Set<Integer> set = new LinkedHashSet<>();
            for (int type : types) {
                set.add(type);
            }
            return new Options(set);
        }

        /**
         * 在当前配置基础上追加启用类型，返回新配置(原配置不变)。
         *
         * @param types 追加的条码类型码，传 SymbolType.XXX 常量；为空则返回原配置
         * @return 含追加类型的新配置
         */
        public Options plus(int... types) {
            if (types == null || types.length == 0) {
                return this;
            }
            Set<Integer> merged = new LinkedHashSet<>(this.enabledTypes);
            for (int type : types) {
                merged.add(type);
            }
            return new Options(merged);
        }

        /**
         * @return 启用的条码类型码集合(不可变)
         */
        public Set<Integer> enabledTypes() {
            return enabledTypes;
        }
    }

    public static void main(String[] args) {
        List<Barcode> results = detect("F:\\workspace\\workspace-a\\2026-05-05_163050.png");
        System.out.println("共识别到 " + results.size() + " 个条码：");
        for (Barcode barcode : results) {
            System.out.println("----------------------");
            System.out.println("类型名称 : " + barcode.typeName());
            System.out.println("分类      : " + barcode.category());
            System.out.println("内容      : " + barcode.data());
            System.out.println("质量      : " + barcode.quality());
            System.out.println("位置多边形: " + barcode.locationPolygon());
        }
    }
}
