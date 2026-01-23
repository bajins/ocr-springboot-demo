package com.bajins.ocr.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * 图片裁剪和旋转工具类
 * @author bajins.com
 */
public class ImageCropAndRotate {

    /**
     * 处理图片并返回处理后的图片路径
     *
     * @param path 图片路径
     * @return 处理后的图片路径
     * @throws IOException 如果图片处理过程中发生错误
     */
    public static String processImage(String path) throws IOException {
        // 1. 加载原始图片
        File inputFile = new File(path);
        BufferedImage sourceImage = ImageIO.read(inputFile);

        // 参数验证
        if (sourceImage == null) {
            throw new IllegalArgumentException("无法加载图片，请检查文件路径和文件格式。");
        }

        // 2. 定义截取区域的坐标和尺寸 (x（从左到右）, y（从上到下）, 宽度, 高度)
        int cropX = 420;
        int cropY = 5;
        int cropWidth = 250;
        int cropHeight = 170;
        // 定义截取区域 (x, y, width, height)
        // Rectangle cropArea = new Rectangle(100, 100, 200, 300);

        // 检查截取区域是否在图片范围内
        if (cropX + cropWidth > sourceImage.getWidth() || cropY + cropHeight > sourceImage.getHeight()) {
            throw new IllegalArgumentException("截取区域超出图片范围。");
        }

        // 截取图片
        BufferedImage croppedImage = sourceImage.getSubimage(cropX, cropY, cropWidth, cropHeight);

        // 3. 将截取后的图片向左旋转90度
        BufferedImage rotatedImage = rotateImageLeft(croppedImage);

        // 4. 动态获取文件格式并生成新文件名
        String fileExtension = getFileExtension(inputFile.getName());
        if (fileExtension.isEmpty()) {
            // 如果没有扩展名，默认使用 png，因为它支持无损压缩和透明度
            fileExtension = "png";
        }

        String outputFileName = generateOutputFileName(inputFile.getName(), "-cr");

        // 5. 将最终处理的图片保存到新文件
        File outputFile = Paths.get(Paths.get(inputFile.getPath()).getParent().toString(), outputFileName).toFile();
        ImageIO.write(rotatedImage, fileExtension, outputFile);
        return outputFile.getAbsolutePath();
    }

    /**
     * 将 BufferedImage 对象向左旋转90度（逆时针）
     *
     * @param image 需要旋转的图片
     * @return 旋转后的图片
     */
    public static BufferedImage rotateImageLeft(BufferedImage image) {
        // 计算旋转后的图片尺寸，旋转角度（度），正数为逆时针，负数为顺时针
        double radians = Math.toRadians(-90);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));

        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();

        // 计算旋转后的图片尺寸
        int rotatedWidth = (int) Math.floor(originalWidth * cos + originalHeight * sin);
        int rotatedHeight = (int) Math.floor(originalHeight * cos + originalWidth * sin);

        // 创建一个新的BufferedImage用于存放旋转后的图片，注意宽高互换
        BufferedImage newImage = new BufferedImage(rotatedWidth, rotatedHeight, image.getType());

        // 获取新图片的Graphics2D对象
        Graphics2D g2d = newImage.createGraphics();

        // 设置高质量渲染参数
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        // g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 创建一个AffineTransform对象用于旋转
        AffineTransform transform = new AffineTransform();

        // 将坐标系原点移动到新图片的中心
        // transform.translate(originalHeight, 0);
        transform.translate(rotatedWidth / 2.0, rotatedHeight / 2.0);
        // 旋转-90度（即逆时针90度）
        transform.rotate(radians);
        // 将坐标系原点移回到绘制图片的左上角
        transform.translate(-originalWidth / 2.0, -originalHeight / 2.0);


        // g2.drawImage(img, transform, null);
        // 将旋转应用到Graphics2D
        g2d.setTransform(transform);

        // g2d.rotate(Math.toRadians(-90), originalHeight / 2.0, originalWidth / 2.0);
        // g2d.translate((originalHeight - originalWidth) / 2.0, (originalWidth - originalHeight) / 2.0);
        // 将原始（裁剪后）的图片绘制到应用了旋转的画布上
        g2d.drawImage(image, 0, 0, null);

        // 释放资源
        g2d.dispose();

        return newImage;
    }

    /**
     * 从文件名中获取文件扩展名（不含点），并转换为小写。
     * @param fileName 文件名
     * @return 文件扩展名 (例如 "jpg", "png")，如果没有则返回空字符串。
     */
    private static String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 根据输入文件名生成输出文件名。
     * 例如 "source.png" -> "source_cropped_rotated.png"
     * @param inputFileName 输入文件名
     * @return 输出文件名
     */
    private static String generateOutputFileName(String inputFileName, String outputExt) {
        int lastDotIndex = inputFileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            String nameWithoutExtension = inputFileName.substring(0, lastDotIndex);
            // 包含点的文件后缀名
            String extension = inputFileName.substring(lastDotIndex);
            return nameWithoutExtension + outputExt + extension;
        } else {
            // 如果没有扩展名，直接在末尾添加后缀
            return inputFileName + outputExt;
        }
    }
}
