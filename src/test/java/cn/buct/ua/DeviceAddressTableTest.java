package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

class DeviceAddressTableTest {
    private Path directory;

    @BeforeEach
    void prepare() throws Exception {
        directory =
                Files.createTempDirectory(
                        Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath()),
                        "device-mapping-");
    }

    @AfterEach
    void clean() throws Exception {
        if (directory == null
                || !directory
                        .normalize()
                        .startsWith(Path.of(".cache/tmp").toAbsolutePath().normalize()))
            throw new IllegalStateException("测试目录越界");
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private Path workbook(boolean legacy, String[][] data) throws Exception {
        Path file = directory.resolve(legacy ? "mapping.xls" : "mapping.xlsx");
        try (Workbook workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook()) {
            var sheet = workbook.createSheet("映射");
            for (int i = 0; i < data.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < data[i].length; j++)
                    if (data[i][j] != null) row.createCell(j).setCellValue(data[i][j]);
            }
            try (var out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }

    @Test
    void bothExcelFormatsSupportHeaderAndKeepNodeIdentifierSpaces() throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            Path file =
                    workbook(
                            legacy,
                            new String[][] {
                                {"设备/变量名称", "地址"},
                                {"", ""},
                                {"  温度  ", "nsu=KEPServerEX;s=Tag 中文;with space "},
                                {"服务器时间", "ns=0;i=2258"}
                            });
            var entries = DeviceAddressTable.read(file);
            assertEquals(
                    List.of(
                            new DeviceAddressTable.Entry(
                                    "温度", "nsu=KEPServerEX;s=Tag 中文;with space "),
                            new DeviceAddressTable.Entry("服务器时间", "ns=0;i=2258")),
                    entries);
            assertThrows(
                    UnsupportedOperationException.class, () -> entries.add(entries.getFirst()));
            Files.delete(file); // 工作簿已关闭，调用方能替换原文件。
        }
    }

    @Test
    void headerlessLegacyTableAndFirstSheetAreSupported() throws Exception {
        Path file =
                workbook(true, new String[][] {{"TEMP_A", "ns=2;s=Channel.Device.Temperature"}});
        assertEquals("TEMP_A", DeviceAddressTable.read(file).getFirst().device());
    }

    @Test
    void rejectsDuplicateNamesPartialRowsAndDaAddressesWithRowNumbers() throws Exception {
        for (String[][] data :
                new String[][][] {
                    {{"设备名称", "地址"}, {"A", "ns=0;i=2258"}, {"A", "ns=0;i=2259"}},
                    {{"设备名称", "地址"}, {"A", ""}},
                    {{"设备名称", "地址"}, {"A", "K0001"}},
                    {{"设备名称", "地址"}, {"A", "A151TT_06102A/ALM1/PV.CV"}},
                    {{"设备名称", "地址"}, {"A", "svr=1;ns=2;s=tag"}}
                }) {
            Path file = workbook(false, data);
            var error = assertThrows(IOException.class, () -> DeviceAddressTable.read(file));
            assertTrue(error.getMessage().contains("行"));
        }
    }

    @Test
    void formulasAndNumericAddressesAreRejectedRatherThanReadFromCachedValues() throws Exception {
        for (boolean formula : new boolean[] {false, true}) {
            Path file = directory.resolve("invalid.xlsx");
            try (var workbook = new XSSFWorkbook()) {
                var row = workbook.createSheet("映射").createRow(0);
                row.createCell(0).setCellValue("A");
                var address = row.createCell(1);
                if (formula) address.setCellFormula("\"ns=0;i=2258\"");
                else address.setCellValue(2258);
                try (var out = Files.newOutputStream(file)) {
                    workbook.write(out);
                }
            }
            var error = assertThrows(IOException.class, () -> DeviceAddressTable.read(file));
            assertTrue(error.getMessage().contains("第1行"));
            assertTrue(error.getMessage().contains("文本"));
        }
    }

    @Test
    void rejectsEmptyInvalidAndOversizedTables() throws Exception {
        assertThrows(
                IOException.class,
                () -> DeviceAddressTable.read(workbook(false, new String[][] {{"设备名称", "地址"}})));
        Path invalid = directory.resolve("broken.xlsx");
        Files.writeString(invalid, "not an Excel file");
        assertThrows(IOException.class, () -> DeviceAddressTable.read(invalid));
        Path large = directory.resolve("large.xls");
        try (var channel =
                java.nio.channels.FileChannel.open(
                        large,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(20L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(IOException.class, () -> DeviceAddressTable.read(large));
    }

    @Test
    void importConvertsLegacyWorkbookWithoutChangingSourceAndReplacesLocalTable() throws Exception {
        Path source = workbook(true, new String[][] {{"设备A", "ns=0;i=2258"}});
        byte[] original = Files.readAllBytes(source);
        Path local = directory.resolve("local/device-addresses.xlsx");
        var entries = DeviceAddressTable.importFile(source, local);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(entries, DeviceAddressTable.read(local));
        try (var normalized =
                org.apache.poi.ss.usermodel.WorkbookFactory.create(local.toFile(), null, true)) {
            assertInstanceOf(XSSFWorkbook.class, normalized);
            assertEquals(
                    "设备名称", normalized.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals(2, normalized.getSheetAt(0).getRow(1).getLastCellNum());
        }
        source = workbook(true, new String[][] {{"设备B", "ns=0;i=2259"}});
        DeviceAddressTable.importFile(source, local);
        assertEquals("设备B", DeviceAddressTable.read(local).getFirst().device());
        try (var files = Files.list(local.getParent())) {
            assertEquals(
                    List.of("device-addresses.xlsx"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void invalidImportPreservesOldMappingAndFailedDestinationDoesNotLeaveTemporaryFiles()
            throws Exception {
        Path valid = workbook(false, new String[][] {{"A", "ns=0;i=2258"}});
        Path local = directory.resolve("local/device-addresses.xlsx");
        DeviceAddressTable.importFile(valid, local);
        byte[] original = Files.readAllBytes(local);
        Path invalid = workbook(false, new String[][] {{"A", "K0001"}});
        assertThrows(IOException.class, () -> DeviceAddressTable.importFile(invalid, local));
        assertArrayEquals(original, Files.readAllBytes(local));
        Path validAgain = workbook(false, new String[][] {{"A", "ns=0;i=2258"}});
        assertThrows(
                IOException.class, () -> DeviceAddressTable.importFile(validAgain, validAgain));
        Path blocked = Files.createDirectory(directory.resolve("local/blocked.xlsx"));
        assertThrows(IOException.class, () -> DeviceAddressTable.importFile(validAgain, blocked));
        try (var files = Files.list(local.getParent())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void shippedTemplateContainsOnlyExplicitExamples() throws Exception {
        var entries = DeviceAddressTable.read(Path.of("config/templates/device-addresses.xlsx"));
        assertEquals(10, entries.size());
        assertEquals("ns=0;i=2258", entries.get(0).address());
        assertEquals("nsu=KEPServerEX;s=WriteDemo.Device1.sim", entries.get(1).address());
        assertEquals("sim", entries.get(1).device());
        assertEquals("DOGKEY", entries.get(2).device());
        assertTrue(
                entries.subList(2, 10).stream()
                        .allMatch(
                                e ->
                                        e.address()
                                                .equals(
                                                        "nsu=KEPServerEX;s=DemoFurnace.Device1."
                                                                + e.device())));
    }
}
