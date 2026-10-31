package cn.buct.ua;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 第一张工作表前两列：设备/业务名称与完整UA NodeId；只读取映射，不连接或写入服务器。 */
public final class DeviceAddressTable {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    private static final Set<String> DEVICE_HEADERS =
            Set.of(
                    "设备",
                    "设备名称",
                    "设备/变量名称",
                    "设备/变量名",
                    "变量名",
                    "变量",
                    "变量名称",
                    "名称",
                    "device",
                    "device name",
                    "devicename",
                    "name",
                    "variable",
                    "variablename",
                    "tag");
    private static final Set<String> ADDRESS_HEADERS =
            Set.of(
                    "地址",
                    "设备地址",
                    "点位地址",
                    "节点地址",
                    "节点标识",
                    "ua地址",
                    "nodeid",
                    "node id",
                    "address",
                    "ua nodeid");

    private DeviceAddressTable() {}

    public record Entry(String device, String address) {
        @Override
        public String toString() {
            return device;
        }
    }

    /** 校验成功后原子替换本机规范表；不改源文件，不生成备份。 */
    public static List<Entry> importFile(Path source, Path target) throws IOException {
        List<Entry> entries = read(source);
        if (target == null
                || !target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new IOException("本机映射表目标必须为 .xlsx 文件。");
        Path destination = target.toAbsolutePath().normalize();
        if (Files.exists(destination) && Files.isSameFile(source, destination))
            throw new IOException("请选择外部源表；导入目标不能与源文件相同。");
        if (Files.exists(destination) && !Files.isRegularFile(destination))
            throw new IOException("本机映射表目标不是普通文件。");
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "device-map-", ".tmp");
        try {
            try (var workbook = new XSSFWorkbook()) {
                var sheet = workbook.createSheet("设备地址");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("设备名称");
                header.createCell(1).setCellValue("地址");
                for (int i = 0; i < entries.size(); i++) {
                    var row = sheet.createRow(i + 1);
                    row.createCell(0).setCellValue(entries.get(i).device());
                    row.createCell(1).setCellValue(entries.get(i).address());
                }
                try (var out = Files.newOutputStream(temporary)) {
                    workbook.write(out);
                }
            }
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return entries;
        } catch (RuntimeException e) {
            throw new IOException("无法保存本机设备地址表，原映射未替换。", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static List<Entry> read(Path file) throws IOException {
        if (file == null) throw new IOException("请选择Excel设备地址表。");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls"))
            throw new IOException("设备地址表必须为 .xlsx 或 .xls 文件。");
        if (!Files.isRegularFile(file) || Files.size(file) == 0)
            throw new IOException("设备地址表不存在、不是普通文件或内容为空。");
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("设备地址表超过20MB，请拆分后导入。");
        try (var workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            if (workbook.getNumberOfSheets() == 0) throw new IOException("Excel没有工作表。");
            var sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_ROWS) throw new IOException("第一张工作表超过100001行，请拆分后导入。");
            var entries = new ArrayList<Entry>();
            var names = new HashSet<String>();
            boolean first = true;
            for (var row : sheet) {
                int line = row.getRowNum() + 1;
                String device = text(row.getCell(0), line, "设备名称").trim();
                String address = text(row.getCell(1), line, "地址");
                if (device.isEmpty() && address.isBlank()) continue;
                if (first) {
                    first = false;
                    if (DEVICE_HEADERS.contains(device.toLowerCase(Locale.ROOT))
                            && ADDRESS_HEADERS.contains(address.trim().toLowerCase(Locale.ROOT)))
                        continue;
                }
                if (device.isEmpty() || address.isBlank()) throw invalid(line, "设备名称和地址必须同时填写。");
                if (!names.add(device)) throw invalid(line, "设备名称重复：" + device);
                try {
                    UaAddress.parts(address);
                } catch (IllegalArgumentException e) {
                    throw new IOException(
                            "第" + line + "行地址不是有效的完整OPC UA NodeId；请向现场确认，不能直接使用DA位号或K0001。", e);
                }
                if (entries.size() == MAX_ROWS) throw invalid(line, "有效设备地址超过100000条，请拆分后导入。");
                entries.add(new Entry(device, address));
            }
            if (entries.isEmpty()) throw new IOException("第一张工作表没有有效的设备地址映射。");
            return List.copyOf(entries);
        } catch (RuntimeException e) {
            throw new IOException("无法读取Excel，请确认它是未加密且未损坏的 .xlsx/.xls 文件。", e);
        }
    }

    private static String text(Cell cell, int line, String field) throws IOException {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() != CellType.STRING)
            throw invalid(line, field + "必须是文本单元格，不支持公式、数字或错误单元格。");
        return cell.getStringCellValue();
    }

    private static IOException invalid(int line, String message) {
        return new IOException("第" + line + "行：" + message);
    }
}
