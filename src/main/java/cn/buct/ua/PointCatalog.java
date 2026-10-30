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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** 将用户选中的发现结果增量合并进点表，不改已有业务属性。 */
public final class PointCatalog {
    private final Path file;
    private byte[] snapshot;
    private boolean existed;
    private String original;
    private Properties properties;
    private List<PointDefinition> existing;

    public record SaveResult(int added, int existing, Path file) {}

    public PointCatalog(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        existed = Files.exists(this.file);
        snapshot = existed ? Files.readAllBytes(this.file) : new byte[0];
        original = new String(snapshot, StandardCharsets.UTF_8);
        properties = parseProperties(original);
        existing = parsePoints(properties);
    }

    public List<PointDefinition> existingPoints() {
        return existing;
    }

    public synchronized SaveResult save(List<DiscoveredPoint> selected) throws Exception {
        if (selected == null || selected.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个变量");
        }
        return locked(() -> saveLocked(List.copyOf(selected)));
    }

    /** 只编辑已有点的应用写授权和范围，不改服务器标识、类型或自定义属性。 */
    public synchronized PointDefinition configure(
            String alias, boolean writable, String minimum, String maximum) throws Exception {
        PointDefinition point =
                existing.stream()
                        .filter(p -> p.alias().equals(alias))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("点位已不存在，请重载点表"));
        String min = minimum == null ? "" : minimum.trim();
        String max = maximum == null ? "" : maximum.trim();
        if (!min.isEmpty() || !max.isEmpty()) {
            if (min.isEmpty()
                    || max.isEmpty()
                    || new java.math.BigDecimal(min).compareTo(new java.math.BigDecimal(max)) > 0)
                throw new IllegalArgumentException("请填写有效的最小值和最大值");
        }
        var updated =
                new PointDefinition(
                        point.alias(),
                        point.namespaceUri(),
                        point.identifierType(),
                        point.identifier(),
                        point.dataType(),
                        writable,
                        min.isEmpty() ? null : min,
                        max.isEmpty() ? null : max);
        return locked(
                () -> {
                    verifySnapshot();
                    String prefix = "node." + alias + ".";
                    replace(
                            ClientConfiguration.merge(
                                    original,
                                    Map.of(
                                            prefix + "writable",
                                            Boolean.toString(writable),
                                            prefix + "minimum",
                                            min,
                                            prefix + "maximum",
                                            max)));
                    return updated;
                });
    }

    private <T> T locked(java.util.concurrent.Callable<T> task) throws Exception {
        Files.createDirectories(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName() + ".catalog.lock");
        try (var channel =
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("点表正由其他窗口保存，请稍后重试");
                return task.call();
            } catch (OverlappingFileLockException e) {
                throw new IOException("点表正由其他窗口保存，请稍后重试", e);
            }
        }
    }

    private SaveResult saveLocked(List<DiscoveredPoint> selected) throws Exception {
        verifySnapshot();
        Set<String> keys = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (PointDefinition point : existing) {
            keys.add(
                    DiscoveredPoint.keyOf(
                            point.namespaceUri(), point.identifierType(), point.identifier()));
            aliases.add(point.alias());
            names.add(point.alias());
        }
        // 未列入 nodes 的已有属性也不得被自动别名覆盖。
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("node.")) {
                String suffix = name.substring(5);
                int end = suffix.indexOf('.');
                if (end > 0) aliases.add(suffix.substring(0, end));
            }
        }
        var additions = new LinkedHashMap<String, DiscoveredPoint>();
        Set<String> submittedKeys = new HashSet<>();
        int duplicateCount = 0;
        for (DiscoveredPoint point : selected) {
            String key = point.key();
            if (!submittedKeys.add(key)) continue;
            if (!keys.add(key)) {
                duplicateCount++;
                continue;
            }
            String alias = alias(point.displayName(), aliases);
            aliases.add(alias);
            names.add(alias);
            additions.put(alias, point);
        }
        if (additions.isEmpty()) return new SaveResult(0, duplicateCount, file);

        String merged = updateNodes(original, String.join(",", names));
        var result = new StringBuilder(merged);
        if (!merged.isEmpty() && !merged.endsWith("\n") && !merged.endsWith("\r"))
            result.append('\n');
        result.append("\n# 由点位发现新增；服务端访问权限仅作记录，应用写入默认关闭。\n");
        for (var entry : additions.entrySet()) {
            String prefix = "node." + entry.getKey() + ".";
            DiscoveredPoint point = entry.getValue();
            append(result, prefix + "namespaceUri", point.namespaceUri());
            append(result, prefix + "identifierType", point.identifierType());
            append(result, prefix + "identifier", point.identifier());
            append(result, prefix + "dataType", point.dataType());
            append(result, prefix + "writable", "false");
            append(result, prefix + "displayName", point.displayName());
            append(result, prefix + "valueRank", Integer.toString(point.valueRank()));
            append(result, prefix + "serverReadable", Boolean.toString(point.readable()));
            append(result, prefix + "serverWritable", Boolean.toString(point.serverWritable()));
            result.append('\n');
        }
        String output = result.toString().stripTrailing() + "\n";
        replace(output);
        return new SaveResult(additions.size(), duplicateCount, file);
    }

    private void replace(String output) throws Exception {
        Properties validated = parseProperties(output);
        List<PointDefinition> checked = parsePoints(validated);
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), "catalog-", ".tmp");
        try {
            Files.write(temporary, bytes);
            verifySnapshot();
            if (existed) {
                Path backup = file.resolveSibling("catalog-backup-" + UUID.randomUUID() + ".bak");
                Files.write(
                        backup, snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            // 不使用非原子的降级覆盖；失败时原点表仍然存在。
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            snapshot = bytes;
            existed = true;
            original = output;
            properties = validated;
            existing = checked;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void verifySnapshot() throws IOException {
        boolean currentExists = Files.exists(file);
        if (currentExists != existed
                || (currentExists && !Arrays.equals(snapshot, Files.readAllBytes(file)))) {
            throw new IOException("点表已被外部修改，请关闭点位窗口并重新打开后再保存");
        }
    }

    private static Properties parseProperties(String text) throws IOException {
        var p = new Properties();
        p.load(new StringReader(text));
        return p;
    }

    private static List<PointDefinition> parsePoints(Properties p) {
        String listed = p.getProperty("nodes", "").trim();
        if (listed.isEmpty()) return List.of();
        var points = new ArrayList<PointDefinition>();
        var aliases = new HashSet<String>();
        for (String item : listed.split(",", -1)) {
            String alias = item.trim();
            if (alias.isEmpty() || !aliases.add(alias)) {
                throw new IllegalArgumentException("点位别名为空或重复：" + alias);
            }
            String prefix = "node." + alias + ".";
            String identifier = p.getProperty(prefix + "identifier");
            if (identifier == null || identifier.isEmpty()) {
                throw new IllegalArgumentException("点位缺少 identifier：" + alias);
            }
            points.add(
                    new PointDefinition(
                            alias,
                            ClientSettings.required(p, prefix + "namespaceUri"),
                            ClientSettings.required(p, prefix + "identifierType"),
                            identifier,
                            ClientSettings.required(p, prefix + "dataType"),
                            ClientSettings.bool(p, prefix + "writable", false),
                            p.getProperty(prefix + "minimum"),
                            p.getProperty(prefix + "maximum")));
        }
        return List.copyOf(points);
    }

    private static String alias(String displayName, Set<String> used) {
        String base = displayName.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        base = base.replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "point";
        if (!Character.isLetter(base.charAt(0))) base = "point_" + base;
        if (base.length() > 48) base = base.substring(0, 48);
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }

    private static void append(StringBuilder target, String key, String value) {
        target.append(escape(key)).append('=').append(escape(value)).append('\n');
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

    /** 只替换最后生效的 nodes 属性，保留其他属性、注释及其原始转义。 */
    private static String updateNodes(String text, String nodes) throws IOException {
        int foundStart = -1;
        int foundEnd = -1;
        int start = 0;
        while (start < text.length()) {
            int end = nextLineEnd(text, start);
            String first = text.substring(start, end).stripLeading();
            boolean comment = first.startsWith("#") || first.startsWith("!");
            while (!comment && continues(text.substring(start, end)) && end < text.length()) {
                end = nextLineEnd(text, end);
            }
            Properties chunk = parseProperties(text.substring(start, end));
            if (chunk.containsKey("nodes")) {
                foundStart = start;
                foundEnd = end;
            }
            start = end;
        }
        String replacement = "nodes=" + escape(nodes) + "\n";
        if (foundStart >= 0)
            return text.substring(0, foundStart) + replacement + text.substring(foundEnd);
        String separator = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r") ? "" : "\n";
        return text + separator + replacement;
    }

    private static int nextLineEnd(String text, int start) {
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
}
