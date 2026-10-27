package cn.buct.ua;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.bouncycastle.util.IPAddress;

/** 证书生成和现有 PKCS12 身份的部署参数；不保存口令。 */
public record PkiSettings(String fileName, String alias, String commonName, String organization,
        List<String> dnsNames, List<String> ipAddresses, int validityDays) {
    public PkiSettings {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || fileName.endsWith(".")
                || fileName.equalsIgnoreCase("client.der")
                || fileName.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
            throw new IllegalArgumentException("pki.file 必须是可移植的英文文件名，且不能占用公开证书 client.der");
        }
        if (alias == null || alias.isBlank() || commonName == null || commonName.isBlank()
                || organization == null || organization.isBlank()) {
            throw new IllegalArgumentException("证书别名、名称和组织不能为空");
        }
        dnsNames = List.copyOf(dnsNames);
        ipAddresses = List.copyOf(ipAddresses);
        if (dnsNames.stream().anyMatch(String::isBlank)
                || ipAddresses.stream().anyMatch(ip -> !IPAddress.isValid(ip))) {
            throw new IllegalArgumentException("证书 DNS 名不能为空，IP SAN 必须是有效 IPv4/IPv6 地址");
        }
        if (validityDays < 1 || validityDays > 3650) throw new IllegalArgumentException("证书有效天数必须为 1—3650");
    }

    static PkiSettings defaults(String applicationName) {
        return from(new Properties(), applicationName);
    }

    static PkiSettings from(Properties p, String applicationName) {
        return new PkiSettings(p.getProperty("pki.file", "client.p12").trim(),
                p.getProperty("pki.alias", "ua-client").trim(),
                p.getProperty("pki.commonName", applicationName).trim(),
                p.getProperty("pki.organization", "UA Development").trim(),
                names(p.getProperty("pki.dnsNames", "")), names(p.getProperty("pki.ipAddresses", "")),
                Integer.parseInt(p.getProperty("pki.validityDays", "365").trim()));
    }

    private static List<String> names(String text) {
        if (text.isBlank()) return List.of();
        return Arrays.stream(text.split(",", -1)).map(String::trim).toList();
    }
}
