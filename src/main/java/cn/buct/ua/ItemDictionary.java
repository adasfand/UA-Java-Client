package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 对应旧C#的itemDictionary地址字典，以及每轮更新的Data内存字典。 */
public final class ItemDictionary {
    private final Map<String, String> itemDictionary;
    private final int batchSize;
    private volatile Snapshot latest = new Snapshot(Map.of(), Map.of(), null);

    public record Options(int intervalMs, int batchSize) {
        public Options {
            if (intervalMs < 100 || intervalMs > 3_600_000)
                throw new IllegalArgumentException("acquisition.intervalMs必须在100至3600000之间。");
            if (batchSize < 1 || batchSize > 1000)
                throw new IllegalArgumentException("acquisition.batchSize必须在1至1000之间。");
        }

        static Options load(Path config) throws IOException {
            var p = ClientSettings.read(config);
            try {
                return new Options(
                        Integer.parseInt(p.getProperty("acquisition.intervalMs", "2000").trim()),
                        Integer.parseInt(p.getProperty("acquisition.batchSize", "256").trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("采集周期和批次大小必须为整数。", e);
            }
        }
    }

    public record Snapshot(
            Map<String, DataValue> samples, Map<String, String> errors, Instant collectedAt) {
        public Snapshot {
            samples = Collections.unmodifiableMap(new LinkedHashMap<>(samples));
            errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        }

        /** 只提供本轮Good值；失败点不会继续充当正常值。允许合法的Good空值。 */
        public Map<String, Object> data() {
            var values = new LinkedHashMap<String, Object>();
            samples.forEach(
                    (name, sample) ->
                            values.put(
                                    name,
                                    sample.getValue() == null
                                            ? null
                                            : sample.getValue().getValue()));
            return Collections.unmodifiableMap(values);
        }
    }

    public ItemDictionary(List<DeviceAddressTable.Entry> entries) {
        this(entries, 256);
    }

    public ItemDictionary(List<DeviceAddressTable.Entry> entries, int batchSize) {
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("批次大小必须在1至1000之间。");
        this.batchSize = batchSize;
        var addresses = new LinkedHashMap<String, String>();
        for (var entry : List.copyOf(entries)) {
            if (entry.device() == null || entry.device().isBlank())
                throw new IllegalArgumentException("变量名不能为空。");
            UaAddress.parse(entry.address());
            if (addresses.putIfAbsent(entry.device(), entry.address()) != null)
                throw new IllegalArgumentException("变量名重复：" + entry.device());
        }
        itemDictionary = Collections.unmodifiableMap(addresses);
    }

    public static ItemDictionary load(Path excel) throws IOException {
        return new ItemDictionary(DeviceAddressTable.read(excel));
    }

    public Map<String, String> itemDictionary() {
        return itemDictionary;
    }

    public Snapshot snapshot() {
        return latest;
    }

    public Map<String, Object> data() {
        return latest.data();
    }

    public Object getValue(String name) {
        if (!itemDictionary.containsKey(name))
            throw new IllegalArgumentException("地址表中没有变量：" + name);
        Snapshot snapshot = latest;
        if (!snapshot.samples().containsKey(name))
            throw new IllegalStateException(
                    "变量尚未成功采集：" + name + "；" + snapshot.errors().getOrDefault(name, "请先执行数据读取。"));
        DataValue sample = snapshot.samples().get(name);
        return sample.getValue() == null ? null : sample.getValue().getValue();
    }

    public double getDouble(String name) {
        Object value = getValue(name);
        if (!(value instanceof Number number))
            throw new IllegalArgumentException("变量不是数值类型：" + name);
        return number.doubleValue();
    }

    public synchronized void clear() {
        latest = new Snapshot(Map.of(), Map.of(), null);
    }

    /** 读取整张表并原子发布一轮Data；调用方决定定时周期，不自动写服务器。 */
    public synchronized Snapshot refresh(UaClient client) {
        Objects.requireNonNull(client, "client");
        var names = new ArrayList<>(itemDictionary.keySet());
        var samples = new LinkedHashMap<String, DataValue>();
        var errors = new LinkedHashMap<String, String>();
        for (int start = 0; start < names.size(); start += batchSize) {
            var batch = names.subList(start, Math.min(start + batchSize, names.size()));
            if (Thread.currentThread().isInterrupted()) {
                for (int i = start; i < names.size(); i++) errors.put(names.get(i), "采集已中断。");
                break;
            }
            try {
                var results = client.readMany(batch.stream().map(itemDictionary::get).toList());
                for (int i = 0; i < batch.size(); i++) {
                    DataValue sample = results.get(i);
                    if (sample == null || sample.getStatusCode() == null)
                        errors.put(batch.get(i), "读取结果缺少值或质量码。");
                    else if (!sample.getStatusCode().isGood())
                        errors.put(batch.get(i), sample.getStatusCode().toString());
                    else samples.put(batch.get(i), sample);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String reason = readFailure(e);
                for (String name : batch) errors.put(name, reason);
                // 传输/服务整体失败后不继续对同一连接发起大量请求，避免每批累积超时。
                for (int i = start + batch.size(); i < names.size(); i++)
                    errors.put(names.get(i), "前一批读取失败，本轮未读取：" + reason);
                break;
            }
        }
        latest = new Snapshot(samples, errors, Instant.now());
        return latest;
    }

    private static String readFailure(Exception error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UaException ua) return ua.getStatusCode().toString();
            if ((t instanceof IllegalArgumentException || t instanceof IllegalStateException)
                    && t.getStackTrace().length > 0
                    && t.getStackTrace()[0].getClassName().startsWith("cn.buct.ua."))
                return t.getMessage();
        }
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return "读取请求失败：" + cause.getClass().getSimpleName();
    }
}
