package cn.buct.ua;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

/** 按命名空间 URI 定位；保留 UA 原始类型，避免统一转换成 double。 */
public record PointDefinition(String alias, String namespaceUri, String identifierType,
        String identifier, String dataType, boolean writable, String minimum, String maximum) {
    public PointDefinition {
        switch (identifierType) {
            case "i" -> Unsigned.uint(identifier);
            case "s" -> { if (identifier.isEmpty()) throw new IllegalArgumentException("字符串标识符不能为空"); }
            case "g" -> { if (!UUID.fromString(identifier).toString().equalsIgnoreCase(identifier)) throw new IllegalArgumentException("GUID 格式不完整"); }
            case "b" -> { if (Base64.getDecoder().decode(identifier).length == 0) throw new IllegalArgumentException("ByteString 标识符不能为空"); }
            default -> throw new IllegalArgumentException("点位标识符仅支持 i、s、g 或 b：" + alias);
        }
        if (writable && !dataType.equals("Boolean") && !dataType.equals("String")) {
            if (minimum == null || maximum == null || new BigDecimal(minimum).compareTo(new BigDecimal(maximum)) > 0) {
                throw new IllegalArgumentException("数值写入点必须配置有效 minimum/maximum：" + alias);
            }
        }
    }

    public NodeId nodeId(int namespaceIndex) {
        return switch (identifierType) {
            case "i" -> new NodeId(namespaceIndex, Unsigned.uint(identifier));
            case "s" -> new NodeId(namespaceIndex, identifier);
            case "g" -> new NodeId(namespaceIndex, UUID.fromString(identifier));
            case "b" -> new NodeId(namespaceIndex, ByteString.of(Base64.getDecoder().decode(identifier)));
            default -> throw new IllegalStateException("未验证的标识符类型");
        };
    }

    public Object parseWriteValue(String text) {
        if (dataType.equals("Boolean")) {
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Boolean 只能写 true 或 false");
            }
            return Boolean.parseBoolean(text);
        }
        if (dataType.equals("String")) return text;
        BigDecimal value = new BigDecimal(text);
        if (minimum == null || maximum == null || value.compareTo(new BigDecimal(minimum)) < 0
                || value.compareTo(new BigDecimal(maximum)) > 0) {
            throw new IllegalArgumentException("写入值超出点位配置范围：" + alias);
        }
        return switch (dataType) {
            case "SByte" -> value.byteValueExact();
            case "Byte" -> Unsigned.ubyte(value.intValueExact());
            case "Int16" -> value.shortValueExact();
            case "UInt16" -> Unsigned.ushort(value.intValueExact());
            case "Int32" -> value.intValueExact();
            case "UInt32" -> Unsigned.uint(value.longValueExact());
            case "Int64" -> value.longValueExact();
            case "UInt64" -> Unsigned.ulong(value.toBigIntegerExact());
            case "Float" -> finiteFloat(value);
            case "Double" -> finiteDouble(value);
            default -> throw new IllegalArgumentException("首版不支持此类型的写入：" + dataType);
        };
    }

    private float finiteFloat(BigDecimal value) {
        float f = value.floatValue();
        if (!Float.isFinite(f)) throw new IllegalArgumentException("Float 超出有限值范围");
        requireConvertedRange(value, new BigDecimal((double) f));
        return f;
    }

    private double finiteDouble(BigDecimal value) {
        double d = value.doubleValue();
        if (!Double.isFinite(d)) throw new IllegalArgumentException("Double 超出有限值范围");
        requireConvertedRange(value, new BigDecimal(d));
        return d;
    }

    private void requireConvertedRange(BigDecimal requested, BigDecimal actual) {
        if ((requested.signum() != 0 && actual.signum() == 0)
                || actual.compareTo(new BigDecimal(minimum)) < 0 || actual.compareTo(new BigDecimal(maximum)) > 0) {
            throw new IllegalArgumentException("浮点转换后的实际值下溢或超出范围，未写入：" + alias);
        }
    }
}
