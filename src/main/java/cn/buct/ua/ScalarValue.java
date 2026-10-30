package cn.buct.ua;

import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

import java.math.BigDecimal;

/** 将调用参数转换为服务端声明的内置标量类型，不依赖点表或模拟器配置。 */
final class ScalarValue {
    private ScalarValue() {}

    static Object convert(NodeId serverType, Object value) {
        if (serverType == null
                || serverType.getNamespaceIndex().intValue() != 0
                || !(serverType.getIdentifier() instanceof UInteger type)) {
            throw new IllegalArgumentException("暂不支持自定义或复杂数据类型的写入");
        }
        if (value == null || value.getClass().isArray()) {
            throw new IllegalArgumentException("写入值必须是非空标量，不支持数组");
        }
        int id = type.intValue();
        if (id == 1) {
            if (value instanceof Boolean) return value;
            if (value instanceof String text) {
                if (text.trim().equalsIgnoreCase("true")) return true;
                if (text.trim().equalsIgnoreCase("false")) return false;
            }
            throw new IllegalArgumentException("Boolean 只接受 Boolean 或 true/false 文本");
        }
        if (id == 12) {
            if (value instanceof String) return value;
            throw new IllegalArgumentException("String 写入只接受字符串参数");
        }
        if (id < 2 || id > 11) {
            throw new IllegalArgumentException("暂不支持此服务端数据类型的标量写入：" + serverType);
        }
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new IllegalArgumentException("数值写入只接受 Number 或数值文本");
        }
        try {
            BigDecimal number =
                    value instanceof BigDecimal decimal
                            ? decimal
                            : new BigDecimal(value.toString().trim());
            return switch (id) {
                case 2 -> number.byteValueExact();
                case 3 -> Unsigned.ubyte(number.intValueExact());
                case 4 -> number.shortValueExact();
                case 5 -> Unsigned.ushort(number.intValueExact());
                case 6 -> number.intValueExact();
                case 7 -> Unsigned.uint(number.longValueExact());
                case 8 -> number.longValueExact();
                case 9 -> Unsigned.ulong(number.toBigIntegerExact());
                case 10 -> finiteFloat(number);
                case 11 -> finiteDouble(number);
                default -> throw new IllegalArgumentException("不支持此服务端数值类型");
            };
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("写入值格式无效、含有小数或超出服务端数值类型范围", e);
        }
    }

    private static float finiteFloat(BigDecimal number) {
        float converted = number.floatValue();
        if (!Float.isFinite(converted) || (number.signum() != 0 && converted == 0.0f)) {
            throw new IllegalArgumentException("Float 写入值溢出或非零值下溢为零");
        }
        return converted;
    }

    private static double finiteDouble(BigDecimal number) {
        double converted = number.doubleValue();
        if (!Double.isFinite(converted) || (number.signum() != 0 && converted == 0.0)) {
            throw new IllegalArgumentException("Double 写入值溢出或非零值下溢为零");
        }
        return converted;
    }
}
