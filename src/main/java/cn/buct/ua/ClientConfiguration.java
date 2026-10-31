package cn.buct.ua;

import java.io.IOException;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** UI 与本地配置共用的快照；保存时校验、检测冲突并原子替换。 */
public final class ClientConfiguration {
    private final Path file;
    private byte[] snapshot;
    private String original;
    private Properties properties;

    public ClientConfiguration(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        snapshot = Files.readAllBytes(this.file);
        original = new String(snapshot, StandardCharsets.UTF_8);
        properties = parse(original);
    }

    public Path file() {
        return file;
    }

    public synchronized Map<String, String> values() {
        var result = new TreeMap<String, String>();
        properties
                .stringPropertyNames()
                .forEach(key -> result.put(key, properties.getProperty(key)));
        return Map.copyOf(result);
    }

    public synchronized ClientSettings settings() {
        return ClientSettings.from(properties, file);
    }

    /** changes 只包含需更新的键；字符串路径原样存储，不从解析后的绝对路径反写。 */
    public synchronized ClientSettings save(Map<String, String> changes) throws IOException {
        Objects.requireNonNull(changes, "changes");
        var submitted = new TreeMap<String, String>();
        changes.forEach(
                (key, value) -> {
                    if (key == null || key.isBlank() || value == null)
                        throw new IllegalArgumentException("配置键不能为空，配置值不能为 null");
                    submitted.put(key, value);
                });
        String output = merge(original, submitted);
        Properties checked = parse(output);
        validateCredentialFields(checked);
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        Path lockFile = file.resolveSibling(file.getFileName() + ".catalog.lock");
        try (var channel =
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("连接配置正由其他窗口保存，请稍后重试");
                verifySnapshot();
                Path temporary = Files.createTempFile(file.getParent(), "client-config-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    // 与原配置位于同目录，保证相对路径的解析含义一致。
                    ClientSettings settings = ClientSettings.load(temporary);
                    verifySnapshot();
                    if (Arrays.equals(snapshot, bytes)) return settings;
                    Path backup =
                            file.resolveSibling("config-backup-" + UUID.randomUUID() + ".bak");
                    Files.write(
                            backup,
                            snapshot,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    snapshot = bytes;
                    original = output;
                    properties = checked;
                    return settings;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (OverlappingFileLockException e) {
                throw new IOException("连接配置正由其他窗口保存，请稍后重试", e);
            }
        }
    }

    private void verifySnapshot() throws IOException {
        if (!Files.exists(file) || !Arrays.equals(snapshot, Files.readAllBytes(file)))
            throw new IOException("连接配置已被外部修改，请从文件重新加载后再保存");
    }

    private static void validateCredentialFields(Properties p) {
        for (String key : p.stringPropertyNames()) {
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith(".passwordenv")) {
                if (!p.getProperty(key).matches("[A-Za-z_][A-Za-z0-9_]*"))
                    throw new IllegalArgumentException("密码环境变量必须填写变量名，不能填写密码");
                continue;
            }
            for (String part : normalized.split("[._-]")) {
                if (Set.of(
                                "password",
                                "passwd",
                                "secret",
                                "secrets",
                                "token",
                                "accesstoken",
                                "refreshtoken",
                                "privatekey",
                                "credential",
                                "credentials")
                        .contains(part))
                    throw new IllegalArgumentException("配置不保存密码、令牌或私钥；请使用环境变量或密码窗口");
            }
        }
    }

    static String merge(String text, Map<String, String> changes) throws IOException {
        String newline = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";
        var result = new StringBuilder();
        var updated = new HashSet<String>();
        String lastChunk = "";
        int start = 0;
        while (start < text.length()) {
            int end = lineEnd(text, start);
            String first = text.substring(start, end).stripLeading();
            boolean comment = first.startsWith("#") || first.startsWith("!");
            while (!comment && continues(text.substring(start, end)) && end < text.length())
                end = lineEnd(text, end);
            String chunk = text.substring(start, end);
            Properties parsed = parse(chunk);
            String key = parsed.stringPropertyNames().stream().findFirst().orElse(null);
            if (key != null && changes.containsKey(key)) {
                if (updated.add(key)) {
                    String replacement = escape(key) + "=" + escape(changes.get(key));
                    // 同值且只有一个定义时保留原始缩进和转义。
                    if (propertiesCount(text, key) == 1
                            && changes.get(key).equals(parsed.getProperty(key)))
                        result.append(chunk);
                    else result.append(replacement).append(newline);
                }
            } else result.append(chunk);
            lastChunk = chunk;
            start = end;
        }
        boolean separatorReady = false;
        for (var entry : changes.entrySet()) {
            if (updated.contains(entry.getKey())) continue;
            if (!separatorReady) {
                if (!result.isEmpty()
                        && result.charAt(result.length() - 1) != '\n'
                        && result.charAt(result.length() - 1) != '\r') result.append(newline);
                // 防止原文件以续行符结束时把新增属性吞进上一项的值。
                if (continues(lastChunk)) result.append(newline);
                separatorReady = true;
            }
            result.append(escape(entry.getKey()))
                    .append('=')
                    .append(escape(entry.getValue()))
                    .append(newline);
        }
        return result.toString();
    }

    private static int propertiesCount(String text, String wanted) throws IOException {
        int count = 0;
        int start = 0;
        while (start < text.length()) {
            int end = lineEnd(text, start);
            String first = text.substring(start, end).stripLeading();
            boolean comment = first.startsWith("#") || first.startsWith("!");
            while (!comment && continues(text.substring(start, end)) && end < text.length())
                end = lineEnd(text, end);
            if (parse(text.substring(start, end)).containsKey(wanted)) count++;
            start = end;
        }
        return count;
    }

    private static Properties parse(String text) throws IOException {
        var p = new Properties();
        p.load(new StringReader(text));
        return p;
    }

    private static int lineEnd(String text, int start) {
        int end = start;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
        if (end < text.length()
                && text.charAt(end++) == '\r'
                && end < text.length()
                && text.charAt(end) == '\n') end++;
        return end;
    }

    private static boolean continues(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\r' || line.charAt(end - 1) == '\n')) end--;
        int slashes = 0;
        while (end > 0 && line.charAt(--end) == '\\') slashes++;
        return slashes % 2 == 1;
    }

    private static String escape(String value) {
        var escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case ' ', '=', ':', '#', '!' -> escaped.append('\\').append(c);
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
