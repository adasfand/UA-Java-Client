package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

class ScalarValueTest {
    @Test
    void convertsJavaNumbersAndUiTextToDeclaredScalarType() {
        assertEquals(42, ScalarValue.convert(NodeIds.Int32, 42));
        assertEquals(42, ScalarValue.convert(NodeIds.Int32, " 42 "));
        assertEquals(42, ScalarValue.convert(NodeIds.Int32, new BigDecimal("42.0")));
        assertEquals((byte) 12, ScalarValue.convert(NodeIds.SByte, 12));
        assertEquals((short) 12, ScalarValue.convert(NodeIds.Int16, 12));
        assertEquals(12L, ScalarValue.convert(NodeIds.Int64, 12));
        assertEquals(1.25f, ScalarValue.convert(NodeIds.Float, 1.25f));
        assertEquals(1.25f, ScalarValue.convert(NodeIds.Float, "1.25"));
        assertEquals(1.25, ScalarValue.convert(NodeIds.Double, "1.25"));
    }

    @Test
    void unsignedValuesUseMiloVariantCompatibleWrappers() {
        assertInstanceOf(UByte.class, ScalarValue.convert(NodeIds.Byte, 255));
        assertInstanceOf(UShort.class, ScalarValue.convert(NodeIds.UInt16, 65535));
        assertInstanceOf(UInteger.class, ScalarValue.convert(NodeIds.UInt32, "4294967295"));
        assertInstanceOf(ULong.class, ScalarValue.convert(NodeIds.UInt64, "18446744073709551615"));
        assertEquals("255", ScalarValue.convert(NodeIds.Byte, 255).toString());
        assertEquals("65535", ScalarValue.convert(NodeIds.UInt16, 65535).toString());
        assertEquals("4294967295", ScalarValue.convert(NodeIds.UInt32, "4294967295").toString());
        assertEquals(
                "18446744073709551615",
                ScalarValue.convert(NodeIds.UInt64, new BigInteger("18446744073709551615"))
                        .toString());
        assertEquals(
                65535,
                ScalarValue.convert(NodeIds.Int32, ScalarValue.convert(NodeIds.UInt16, 65535)));
    }

    @Test
    void signedIntegerBoundsAreExact() {
        assertEquals(Byte.MIN_VALUE, ScalarValue.convert(NodeIds.SByte, "-128"));
        assertEquals(Byte.MAX_VALUE, ScalarValue.convert(NodeIds.SByte, "127"));
        assertEquals(Short.MIN_VALUE, ScalarValue.convert(NodeIds.Int16, "-32768"));
        assertEquals(Short.MAX_VALUE, ScalarValue.convert(NodeIds.Int16, "32767"));
        assertEquals(Integer.MIN_VALUE, ScalarValue.convert(NodeIds.Int32, "-2147483648"));
        assertEquals(Integer.MAX_VALUE, ScalarValue.convert(NodeIds.Int32, "2147483647"));
        assertEquals(Long.MIN_VALUE, ScalarValue.convert(NodeIds.Int64, "-9223372036854775808"));
        assertEquals(Long.MAX_VALUE, ScalarValue.convert(NodeIds.Int64, "9223372036854775807"));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.SByte, "-129"));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.SByte, "128"));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Int16, "32768"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.Int32, "2147483648"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.Int64, "9223372036854775808"));
    }

    @Test
    void unsignedIntegersRejectNegativeAndOverMaximumValues() {
        for (var type : List.of(NodeIds.Byte, NodeIds.UInt16, NodeIds.UInt32, NodeIds.UInt64)) {
            assertEquals("0", ScalarValue.convert(type, 0).toString());
            assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(type, -1));
        }
        assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Byte, 256));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.UInt16, 65536));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.UInt32, "4294967296"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.UInt64, "18446744073709551616"));
    }

    @Test
    void integerConversionNeverSilentlyTruncatesFractions() {
        for (var type :
                List.of(
                        NodeIds.SByte,
                        NodeIds.Byte,
                        NodeIds.Int16,
                        NodeIds.UInt16,
                        NodeIds.Int32,
                        NodeIds.UInt32,
                        NodeIds.Int64,
                        NodeIds.UInt64)) {
            assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(type, 1.5));
            assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(type, "1.5"));
        }
    }

    @Test
    void floatingPointRejectsNonFiniteInputsAndOverflow() {
        for (var type : List.of(NodeIds.Float, NodeIds.Double)) {
            for (var value :
                    List.of(
                            Double.NaN,
                            Double.POSITIVE_INFINITY,
                            Double.NEGATIVE_INFINITY,
                            Float.NaN,
                            Float.POSITIVE_INFINITY,
                            Float.NEGATIVE_INFINITY,
                            "NaN",
                            "Infinity",
                            "-Infinity")) {
                assertThrows(
                        IllegalArgumentException.class, () -> ScalarValue.convert(type, value));
            }
        }
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Float, "1e39"));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Double, "1e309"));
    }

    @Test
    void floatingPointRejectsNonzeroUnderflowButAllowsNormalRounding() {
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Float, "1e-50"));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Float, "-1e-50"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.Double, "1e-400"));
        assertEquals(Float.MIN_VALUE, ScalarValue.convert(NodeIds.Float, Float.MIN_VALUE));
        assertEquals(Double.MIN_VALUE, ScalarValue.convert(NodeIds.Double, Double.MIN_VALUE));
        assertEquals(Float.MAX_VALUE, ScalarValue.convert(NodeIds.Float, Float.MAX_VALUE));
        assertEquals(Double.MAX_VALUE, ScalarValue.convert(NodeIds.Double, Double.MAX_VALUE));
        assertEquals(0.1f, ScalarValue.convert(NodeIds.Float, 0.1));
        assertEquals(0.0f, ScalarValue.convert(NodeIds.Float, "0"));
        assertEquals(0.0, ScalarValue.convert(NodeIds.Double, "0"));
    }

    @Test
    void booleanAcceptsOnlyBooleanOrTrueFalseText() {
        assertEquals(true, ScalarValue.convert(NodeIds.Boolean, true));
        assertEquals(false, ScalarValue.convert(NodeIds.Boolean, " false "));
        assertEquals(true, ScalarValue.convert(NodeIds.Boolean, "TRUE"));
        assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Boolean, 1));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Boolean, "0"));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Boolean, "yes"));
    }

    @Test
    void stringValuesPreserveWhitespaceAndDoNotCoerceOtherTypes() {
        assertEquals("  a b  ", ScalarValue.convert(NodeIds.String, "  a b  "));
        assertEquals("", ScalarValue.convert(NodeIds.String, ""));
        assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.String, 42));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.String, true));
    }

    @Test
    void rejectsNullArraysUnknownTypesAndArbitraryObjects() {
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Int32, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.Int32, new int[] {1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.String, new String[] {"x"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(new NodeId(2, "Custom"), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarValue.convert(NodeIds.DateTime, "2026-10-30"));
        assertThrows(IllegalArgumentException.class, () -> ScalarValue.convert(null, 1));
        Object custom =
                new Object() {
                    public String toString() {
                        fail("不应把任意对象偷偷转成文本");
                        return "42";
                    }
                };
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Int32, custom));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.String, custom));
        assertThrows(
                IllegalArgumentException.class, () -> ScalarValue.convert(NodeIds.Int32, true));
    }
}
