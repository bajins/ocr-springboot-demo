package com.bajins.ocr.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * 这个类演示了如何在PDF文件中查找文本，并提供两种方式进行标记：
 * 1. 直接在页面上绘制一个红圈。
 * 2. 添加一个可交互的红色圆形注释。
 */
public class PdfTextHighlighter {

    private static final String TARGET_TEXT = "MP35";

    public static void main(String[] args) throws URISyntaxException, IOException {
        URL url = Thread.currentThread().getContextClassLoader().getResource("pdfs/S203_MB_HWA.0.1.pdf");
        if (url == null) {
            throw new IllegalArgumentException("未找到资源");
        }
        // 自动处理编码、特殊字符
        URI uri = url.toURI();
        File file = new File(uri);
        String outputFilePath = file.getParent() + File.separator + "S203_MB_HWA.0.1-Java.pdf";

        try (PDDocument document = Loader.loadPDF(file)) {

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);

                HashSet<String> uniqueTexts = new HashSet<>();

                int finalI = i + 1;
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                        // System.out.printf("在第 %d 页，正在处理：%s \n", finalI, text);
                        if (!text.equals(TARGET_TEXT)) {
                            return;
                        }
                        float x0 = Float.MAX_VALUE; // 矩形最左边的 X 坐标
                        float y0 = Float.MAX_VALUE; // 矩形最顶边的 Y 坐标
                        float x1 = Float.MIN_VALUE; // 矩形最右边的 X 坐标
                        float y1 = Float.MIN_VALUE; // 矩形最底边的 Y 坐标

                        for (TextPosition pos : textPositions) {
                            /*
                            getX(), getY()	基于页面旋转（pageRotation） 的坐标
                            getEndX(), getEndY()：文本绘制结束点（用于计算宽度）。
                            getXDirAdj(), getYDirAdj()	基于文本自身方向（通过 getDir() 推断） 的坐标（更准确用于布局分析）
                            getDir()：从 textMatrix 推断文本方向（0°、90°、180°、270°）。判断文本是横排还是竖排
                            getWidth(), getHeight()：获取文本宽度/高度（maxHeight 是字体高度）。
                            getWidthDirAdj()：按文本方向调整后的宽度。
                            DirAdj 系列方法是文本布局分析的关键，确保无论页面或文本如何旋转，都能正确比较位置。
                            contains(TextPosition tp2)：判断当前文本是否部分包含另一个文本（重叠 >15%）。
                            completelyContains(TextPosition tp2)：判断是否完全包含另一个文本（用于去重或层级分析）。
                             */
                            x0 = Math.min(x0, pos.getX());
                            y0 = Math.min(y0, pos.getY());
                            x1 = Math.max(x1, pos.getX() + pos.getWidth());
                            y1 = Math.max(y1, pos.getY() + pos.getHeight());

                            // float pageHeight = pos.getPageHeight();
                            // float visualY0 = pageHeight - (y0 + pos.getHeightDir()); // 文本顶部距离页面顶部
                            // float visualY1 = pageHeight - y0;            // 文本底部距离页面顶部
                            // 此时 (x0, visualY0) 是左上角，(x1, visualY1) 是右下角
                        }
                        // 有些系统（如图像坐标系）以左上角为原点，y 向下为正。可以将 y 转换为“从页面顶部起算”：
                        // 坐标系转换 (Y-down -> Y-up) 将这个“左上角”坐标转换为“左下角”坐标以供PDFBox注释使用
                        PDRectangle pageBox = getCurrentPage().getMediaBox();
                        float pageHeight = pageBox.getHeight();
                        // 新矩形的左下角Y = 页面高度 - 旧矩形Y的上边界(y1) 其实就是pos.getEndY()
                        float lowerLeftY = pageHeight - y1;
                        PDRectangle rect = new PDRectangle(x0, lowerLeftY, x1 - x0, y1 - y0);
                        // PDRectangle rect = new PDRectangle(x0, y0, x1, y1);
                        // 去重复
                        String rectString = rect.toString();
                        if (uniqueTexts.contains(rectString)) {
                            return;
                        }
                        uniqueTexts.add(rectString);
                        /*PDPage page = getCurrentPage();
                        PDRectangle cropBox = page.getCropBox();
                        int rotation = page.getRotation();
                        System.out.printf("  - 页面信息 (宽, 高, 旋转): (%.2f, %.2f, %d 度)%n",
                                cropBox.getWidth(), cropBox.getHeight(), rotation);*/

                        System.out.printf("在第 %d 页，坐标 (x0=%.2f, y0=%.2f, x1=%.2f, y1=%.2f) ", finalI, x0, y0, x1, y1);
                        System.out.printf("画圈坐标 (x0=%.2f, y0=%.2f, x1=%.2f, y1=%.2f)%n", rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getUpperRightX(), rect.getUpperRightY());
                        // a) 计算中心点和半径
                        float centerX = rect.getLowerLeftX() + rect.getWidth() / 2;
                        float centerY = rect.getLowerLeftY() + rect.getHeight() / 2;
                        // b) 半径取宽度和高度中的较大者的一半，并增加一点边距
                        float radius = Math.max(rect.getWidth(), rect.getHeight()) / 2 + 2.0f;
                        System.out.printf("  - 中心点 (%.2f, %.2f) 半径 %.2f%n", centerX, centerY, radius);
                        // 以追加模式打开内容流（不会破坏原有图形）
                        try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            contentStream.setStrokingColor(Color.RED);
                            // 设置线条宽度
                            contentStream.setLineWidth(1.0f);
                            // drawEllipse(contentStream, rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getWidth(), rect.getHeight());
                            drawCircle(contentStream, centerX, centerY, radius);
                        }
                        // 为了创建一个正圆形的注释，我们需要基于中心点和半径来构建一个新的正方形PDRectangle
                        // PDRectangle circleAnnotRect = new PDRectangle(centerX - radius, centerY - radius, 2 * radius, 2 * radius);
                        // addCircleAnnotation(page, circleAnnotRect);
                    }
                };

                // 开启排序有助于文本块的正确组合，但也会导致文本块的错误组合。
                // stripper.setSortByPosition(true);
                stripper.setStartPage(finalI);
                stripper.setEndPage(finalI);
                stripper.getText(document);
            }

            // 3. 保存修改后的文件
            document.save(outputFilePath);
            System.out.println("处理完成，文件已保存为: " + outputFilePath);
        }
    }

    /**
     * 在指定页面和位置添加一个红色的、可交互的圆形注释。
     */
    private static void addCircleAnnotation(PDPage page, PDRectangle rect) throws IOException {
        List<PDAnnotation> annotations = page.getAnnotations();

        PDAnnotationSquareCircle circle = new PDAnnotationCircle();
        PDBorderStyleDictionary borderStyle = new PDBorderStyleDictionary();
        borderStyle.setWidth(1.5f); // 设置线宽
        circle.setBorderStyle(borderStyle);

        // 设置位置和大小
        circle.setRectangle(rect);

        // 红色
        PDColor red = new PDColor(new float[]{1, 0, 0}, PDDeviceRGB.INSTANCE);
        // 绿色
        // PDColor green = new PDColor(new float[]{0, 1, 0}, PDDeviceRGB.INSTANCE);
        // 蓝色
        // PDColor blue = new PDColor(new float[]{0, 0, 1}, PDDeviceRGB.INSTANCE);
        // 黑色
        // PDColor black = new PDColor(new float[]{0, 0, 0}, PDDeviceRGB.INSTANCE);
        // 白色
        // PDColor white = new PDColor(new float[]{1, 1, 1}, PDDeviceRGB.INSTANCE);
        // 黄色
        // PDColor yellow = new PDColor(new float[]{1, 1, 0}, PDDeviceRGB.INSTANCE);
        // 设置颜色
        circle.setColor(red);

        // 将新注释添加到页面
        annotations.add(circle);
    }

    /**
     * 在给定的矩形边界内绘制一个椭圆。
     */
    private static void drawEllipse(PDPageContentStream contentStream, float x, float y, float width, float height) throws IOException {
        float margin = 2.0f;
        float rx = width / 2 + margin;
        float ry = height / 2 + margin;
        float cx = x + width / 2;
        float cy = y + height / 2;
        final float K = 0.552284749831f; // 贝塞尔曲线控制点系数（完美圆）
        contentStream.moveTo(cx - rx, cy);
        contentStream.curveTo(cx - rx, cy + K * ry, cx - K * rx, cy + ry, cx, cy + ry);
        contentStream.curveTo(cx + K * rx, cy + ry, cx + rx, cy + K * ry, cx + rx, cy);
        contentStream.curveTo(cx + rx, cy - K * ry, cx + K * rx, cy - ry, cx, cy - ry);
        contentStream.curveTo(cx - K * rx, cy - ry, cx - rx, cy - K * ry, cx - rx, cy);
        // 描边路径
        contentStream.stroke();
    }

    /**
     * 使用给定的中心点和半径直接绘制一个正圆。（使用四段贝塞尔曲线逼近）
     */
    private static void drawCircle(PDPageContentStream contentStream, float cx, float cy, float r) throws IOException {
        final float k = 0.552284749831f; // 贝塞尔曲线控制点系数（完美圆）
        contentStream.moveTo(cx - r, cy);
        contentStream.curveTo(cx - r, cy + k * r, cx - k * r, cy + r, cx, cy + r);
        contentStream.curveTo(cx + k * r, cy + r, cx + r, cy + k * r, cx + r, cy);
        contentStream.curveTo(cx + r, cy - k * r, cx + k * r, cy - r, cx, cy - r);
        contentStream.curveTo(cx - k * r, cy - r, cx - r, cy - k * r, cx - r, cy);
        contentStream.stroke();
    }


    static class SmartCadStripper extends PDFTextStripper {
        private final String keyword;
        private final List<float[]> foundRects = new ArrayList<>();

        // 临时存储当前页面的所有字符
        private List<TextPosition> rawCharacters = new ArrayList<>();

        public SmartCadStripper(String keyword) throws IOException {
            super();
            this.keyword = keyword;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            rawCharacters.clear(); // 新页面开始，清空缓存
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            // 页面结束时，开始处理这一页收集到的所有杂乱字符
            processPageCharacters(getCurrentPageNo() - 1);
            super.endPage(page);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            // 这里不直接处理，而是先收集起来
            // 这样我们就能拿到完全“原始”的数据，没有被 PDFBox 乱排过
            rawCharacters.add(text);
        }

        /**
         * 自定义聚类算法：将碎片化的字符拼成单词
         */
        private void processPageCharacters(int pageIndex) {
            if (rawCharacters.isEmpty()) {
                return;
            }

            // 1. 简单的几何排序：先按 Y 轴排，再按 X 轴排
            // 注意：针对特定方向的图纸（如竖向排版），这里的排序逻辑可能要调整
            Collections.sort(rawCharacters, new Comparator<TextPosition>() {
                @Override
                public int compare(TextPosition o1, TextPosition o2) {
                    // 允许 Y 轴有微小的误差 (比如 2.0f)
                    if (Math.abs(o1.getYDirAdj() - o2.getYDirAdj()) > 2.0f) {
                        return Float.compare(o1.getYDirAdj(), o2.getYDirAdj());
                    }
                    return Float.compare(o1.getXDirAdj(), o2.getXDirAdj());
                }
            });

            // 2. 遍历并合并
            StringBuilder currentWord = new StringBuilder();
            List<TextPosition> currentGroup = new ArrayList<>();

            TextPosition lastChar = null;

            for (TextPosition curChar : rawCharacters) {
                boolean sameGroup = false;

                if (lastChar != null) {
                    // --- 核心判断逻辑：什么样的情况才算“同一个词” ---

                    // A. 距离判断：当前字符的左边 减去 上个字符的右边
                    float gap = curChar.getXDirAdj() - (lastChar.getXDirAdj() + lastChar.getWidthDirAdj());

                    // B. 字体大小判断：大小差异不能超过 10%
                    boolean sizeMatch = Math.abs(curChar.getFontSize() - lastChar.getFontSize()) < 1.0f;

                    // C. 旋转方向判断：必须一致 (0, 90, 180, 270)
                    boolean rotationMatch = curChar.getDir() == lastChar.getDir();

                    // 阈值设定：间距小于字符宽度的 1.0 倍（根据实际图纸调整）
                    // 如果 gap 是负数，说明重叠了（CAD常有的事），也算在一起
                    if (gap < curChar.getFontSizeInPt() * 0.8 && gap > -5 && sizeMatch && rotationMatch) {
                        sameGroup = true;
                    }
                }

                if (sameGroup) {
                    currentWord.append(curChar.getUnicode());
                    currentGroup.add(curChar);
                } else {
                    // 结束上一组，检查是否匹配关键字
                    checkAndSave(currentWord.toString(), currentGroup, pageIndex);

                    // 开启新的一组
                    currentWord = new StringBuilder();
                    currentGroup.clear();
                    currentWord.append(curChar.getUnicode());
                    currentGroup.add(curChar);
                }
                lastChar = curChar;
            }
            // 检查最后一组
            checkAndSave(currentWord.toString(), currentGroup, pageIndex);
        }

        private void checkAndSave(String word, List<TextPosition> group, int pageIdx) {
            if (word.contains(keyword)) {
                // 计算这组字符的整体包围盒
                float minX = Float.MAX_VALUE;
                float minY = Float.MAX_VALUE; // 注意：PDFBox Y轴方向可能需要根据坐标系确认
                float maxX = Float.MIN_VALUE;
                float maxY = Float.MIN_VALUE;

                // 遍历组内所有字符获取最大边界（比单纯取首尾更保险）
                for (TextPosition tp : group) {
                    minX = Math.min(minX, tp.getXDirAdj());
                    // YDirAdj 是基线。顶部是 Y - height (如果坐标原点在左上) 或 Y + height
                    // PDFBox 默认 textPosition.getYDirAdj() 是页面左上角为原点时的 y 坐标 (向下增大)
                    minY = Math.min(minY, tp.getYDirAdj() - tp.getHeightDir());

                    maxX = Math.max(maxX, tp.getXDirAdj() + tp.getWidthDirAdj());
                    maxY = Math.max(maxY, tp.getYDirAdj()); // 基线作为底部
                }

                float w = maxX - minX;
                float h = maxY - minY;

                // 修正：如果计算出的高度异常小（比如全是横线），给个默认值
                if (h < 2) {
                    h = group.get(0).getFontSizeInPt();
                }
                foundRects.add(new float[]{pageIdx, minX, maxY, w, h});
            }
        }

        public List<float[]> getFoundRects() {
            return foundRects;
        }
    }
}