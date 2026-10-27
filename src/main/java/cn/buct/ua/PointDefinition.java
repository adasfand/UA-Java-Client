package cn.buct.ua;

import java.math.BigDecimal;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

/** 按命名空间 URI 定位；保留 UA 原始类型，避免统一转换成 double。 */
public record PointDefinition(String alias, String namespaceUri, String identifierType,
        String identifier, String dataType, boolean writable, String minimum, String maximum) {
    public PointDefinition {
        if (!identifierType.equals("i") && !identifierType.equals("s")) {
            throw new IllegalArgumentException("首版点位标识符仅支持 i 或 s：" + alias);
        }
        if (identifierType.equals("i")) Unsigned.uint(identifier);
        if (writable && !dataType.equals("Boolean") && !dataType.equals("String")) {
            if (minimum == null || maximum == null || new BigDecimal(minimum).compareTo(new BigDecimal(maximum)) > 0) {
                throw new IllegalArgumentException("数值写入点必须配置有效 minimum/maximum：" + alias);
            }
        }
    }

    public NodeId nodeId(int namespaceIndex) {
        return identifierType.equals("i") ? new NodeId(namespaceIndex, Unsigned.uint(identifier))
                : new NodeId(namespaceIndex, identifier);
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
