package com.bajins.ocr.utils.rustpaddleocr;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.WString;
import com.sun.jna.win32.W32APIOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * rust-paddle-ocr 原生库加载器。
 * <p>
 * 负责按当前平台解析动态库文件名、从资源路径提取、配置 DLL 搜索路径并完成 JNA 绑定。
 * 从 {@link OcrCapi} 抽离，使绑定接口仅保留 C API 声明，实现库加载基础设施与 API 声明的职责分离。
 */
public final class OcrNativeLibrary {

    /** 库文件在 classpath 资源中的根目录前缀 */
    private static final String RESOURCE_DIR = "rustpaddleocr/";

    private OcrNativeLibrary() {
    }

    /**
     * 加载并绑定指定 JNA 库接口。
     *
     * @param libDir 显式库目录路径；为 null/空/不存在时从 classpath 资源提取
     * @param clazz  JNA 库接口类型
     * @param <T>    Library 子类型
     * @return 已绑定的库实例
     */
    public static <T extends Library> T load(String libDir, Class<T> clazz) {
        String libName = RESOURCE_DIR + resolvePlatformLibName();
        File dll = resolveLibraryFile(libDir, libName);
        configureSearchPaths(dll);
        return Native.load(dll.getAbsolutePath(), clazz, W32APIOptions.UNICODE_OPTIONS);
    }

    /**
     * 按当前平台返回原生库文件名。
     *
     * @return 平台对应的库文件名（如 ocr_capi-windows-x64.dll）
     */
    private static String resolvePlatformLibName() {
        if (Platform.isMac()) {
            return "libocr_capi.dylib";
        }
        if (Platform.isLinux()) {
            return "libocr_capi.so";
        }
        if (Platform.isWindows()) {
            return "ocr_capi-windows-x64.dll";
        }
        throw new OcrException("Unsupported platform: " + System.getProperty("os.name"));
    }

    /**
     * 解析库文件位置：优先使用显式目录，否则从 classpath 资源提取。
     *
     * @param libDir  显式库目录；可为 null
     * @param libName 资源相对路径名
     * @return 库文件
     */
    private static File resolveLibraryFile(String libDir, String libName) {
        if (libDir != null && !libDir.isEmpty() && Files.exists(Paths.get(libDir))) {
            Path path = Paths.get(libDir);
            // 传入若是文件路径，取其所在目录
            if (!Files.isDirectory(path)) {
                path = path.getParent();
            }
            return path.resolve(libName).toFile();
        }
        try {
            return Native.extractFromResourcePath(libName);
        } catch (IOException e) {
            throw new OcrException("无法从资源路径提取原生库: " + libName, e);
        }
    }

    /**
     * 配置原生库搜索路径，确保依赖 DLL 可被定位。
     *
     * @param dll 主库文件
     */
    private static void configureSearchPaths(File dll) {
        String parent = dll.getParent();
        // 将库目录加入 JNA 搜索路径，使依赖库可被定位
        System.setProperty("jna.library.path",
                parent + File.pathSeparator + System.getProperty("jna.library.path", ""));
        if (Platform.isWindows()) {
            // 扩展 Windows DLL 搜索目录（Unicode 安全的 SetDllDirectoryW，处理依赖库加载）
            NativeLibrary.getInstance("kernel32")
                    .getFunction("SetDllDirectoryW")
                    .invokeInt(new Object[]{new WString(parent)});
        }
    }
}
