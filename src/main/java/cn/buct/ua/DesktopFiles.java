package cn.buct.ua;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 首次桌面启动从共享模板准备本机配置；不覆盖个人文件。 */
final class DesktopFiles {
    private DesktopFiles() {}

    static void prepare(Path configFile, Path templates) throws IOException {
        if (Files.exists(configFile)) return;
        Path parent = configFile.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        copyIfMissing(templates.resolve("client.properties"), configFile);
    }

    private static void copyIfMissing(Path source, Path target) throws IOException {
        try {
            Files.copy(source, target);
        } catch (FileAlreadyExistsException ignored) {
            /* 已有个人配置保持原样。 */
        }
    }
}
