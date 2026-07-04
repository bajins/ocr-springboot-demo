# 项目协作补充规范

本文件补充项目特定的架构模式，与全局 `~/.claude/CLAUDE.md` 共同生效；冲突时以本文件为准。

## 原生库集成模式（JNA / rust-paddle-ocr）

适用于 `src/main/java/com/bajins/ocr/utils/rustpaddleocr/` 及后续新增的原生库集成。

### 三层职责分离（强制）
原生库集成严格分三层，禁止混放：
1. **绑定接口层**（如 `OcrCapi`，`interface extends Library`）：仅声明 JNA 结构体与 C 函数映射，**不得**包含任何库加载逻辑。
2. **库加载层**（如 `OcrNativeLibrary`，`final` 工具类）：负责平台库文件名解析、classpath 资源提取、搜索路径与 DLL 目录配置，对外仅暴露 `load(libDir, clazz)`。
3. **封装层**（如 `PaddleOcrEngine`）：屏蔽 JNA 细节，提供类型安全枚举、流式 `Config`、`record` 结果类、`AutoCloseable` 资源管理。

### 库加载注意
- `jna.library.path` 在 JNA 首次 `Native.load` 时快照，必须在 load 前设置，事后修改对已加载库无效。
- Windows 依赖库搜索用 `SetDllDirectoryW`（传 `new WString(path)`），避免 ANSI 版丢失非 ASCII 字符；**不依赖 `jna-platform`**（未在编译 classpath），用 `NativeLibrary.getInstance("kernel32").getFunction("SetDllDirectoryW")` 反射式调用。
- 接口 `INSTANCE` 在类初始化时加载，库缺失会抛 `ExceptionInInitializerError`，需在文档中提示。

### 直接内存校验
传给 native 的 `ByteBuffer` 容量校验**用 `capacity()` 而非 `remaining()`**：调用方常 `flip()` 重置指针，此时 `remaining()` 归零会误判。校验维度：`isDirect()` + `width/height > 0` + `capacity >= width*height*channels`。

### 资源与内存释放
- 引擎/模型句柄实现 `AutoCloseable`，调用方用 try-with-resources 释放。
- 原生返回的 `ByValue` 结构体结果，在 `finally` 中调用对应的 `free` 函数，确保异常路径也释放 C 内存。
- 原生错误字符串 `Pointer` 读取后立即 `ocr_free_string`，防止泄漏。

### 异常体系
- 业务可预期失败（句柄创建失败、参数非法、原生返回错误）统一抛 `OcrException extends RuntimeException`。
- 不可预期运行时故障（JNA 链接错误等）保留为系统异常，不由 `OcrException` 承载。
- 句柄校验用 `ensureHandle(handle, operation)` 模式：判空 → 读取原生错误 → 抛出带操作语义的异常，替代裸 `RuntimeException` 与 boolean 触发模式。

### 线程安全
- native 引擎默认按非可重入处理，封装类的识别方法加 `synchronized`（实例锁）保护；需更高并发时为每个线程创建独立实例，而非放开锁。

### 配置
- `Config` 全字段可调（fluent setter，均带默认值），保留 `defaultConf()/fast()/gpu()` 等静态工厂；`toNative()` 包级可见，不对外暴露 JNA 结构。

### 构建注意
- `pom.xml` 配置 `<sourceDirectory>.</sourceDirectory>`，导致 `mvn compile` 会把 `src/test/java` 也纳入主源码编译。校验单个文件编译时，用 `mvn dependency:build-classpath` 生成 classpath 后 `javac` 单独编译目标文件，避开无关测试文件的既有错误。
