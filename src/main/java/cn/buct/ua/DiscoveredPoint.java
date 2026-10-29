package cn.buct.ua;

import java.math.BigInteger;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** 服务器公开的变量元数据；服务端写权限不等于应用写授权。 */
public record DiscoveredPoint(
        String displayName,
        String namespaceUri,
        String identifierType,
        String identifier,
        String dataType,
        int valueRank,
        boolean readable,
        boolean serverWritable) {
    public DiscoveredPoint {
        Objects.requireNonNull(displayName, "displayName");
        if (namespaceUri == null
                || namespaceUri.isBlank()
                || dataType == null
                || dataType.isBlank()) {
            throw new IllegalArgumentException("发现的变量缺少命名空间或数据类型");
        }
        Objects.requireNonNull(identifierType, "identifierType");
        Objects.requireNonNull(identifier, "identifier");
        new PointDefinition(
                "discovered",
                namespaceUri,
                identifierType,
                identifier,
                dataType,
                false,
                null,
                null);
    }

    /** 不包含易变的 namespace index，也不依赖可重复的显示名称。 */
    public String key() {
        return keyOf(namespaceUri, identifierType, identifier);
    }

    static String keyOf(String namespaceUri, String identifierType, String identifier) {
        String canonical =
                switch (identifierType) {
                    case "i" -> new BigInteger(identifier).toString();
                    case "g" -> UUID.fromString(identifier).toString();
                    case "b" ->
                            Base64.getEncoder()
                                    .encodeToString(Base64.getDecoder().decode(identifier));
                    case "s" -> identifier;
                    default -> throw new IllegalArgumentException("点位标识符仅支持 i、s、g 或 b");
                };
        return namespaceUri.length() + ":" + namespaceUri + identifierType + ":" + canonical;
    }
}
