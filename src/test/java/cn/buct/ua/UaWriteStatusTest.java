package cn.buct.ua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.structured.ResponseHeader;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteResponse;
import org.junit.jupiter.api.Test;

class UaWriteStatusTest {
    private WriteResponse response(StatusCode service, StatusCode... items) {
        return new WriteResponse(
                new ResponseHeader(DateTime.now(), uint(1), service, null, null, null),
                items,
                null);
    }

    @Test
    void serviceFailureCannotBeHiddenByGoodItemStatus() {
        var bad = new StatusCode(StatusCodes.Bad_UserAccessDenied);
        var error =
                assertThrows(
                        UaException.class,
                        () -> UaConnection.checkedWriteStatus(response(bad, StatusCode.GOOD)));
        assertEquals(bad, error.getStatusCode());
    }

    @Test
    void serviceGoodRequiresExactlyOneConfirmedGoodItem() throws Exception {
        assertEquals(
                StatusCode.GOOD,
                UaConnection.checkedWriteStatus(response(StatusCode.GOOD, StatusCode.GOOD)));
        var bad = new StatusCode(StatusCodes.Bad_NotWritable);
        var error =
                assertThrows(
                        UaException.class,
                        () -> UaConnection.checkedWriteStatus(response(StatusCode.GOOD, bad)));
        assertEquals(bad, error.getStatusCode());
        assertThrows(
                IllegalStateException.class,
                () -> UaConnection.checkedWriteStatus(response(StatusCode.GOOD)));
    }

    @Test
    void missingResponseCannotBeReportedAsSuccess() {
        assertThrows(IllegalStateException.class, () -> UaConnection.checkedWriteStatus(null));
        assertThrows(
                IllegalStateException.class,
                () ->
                        UaConnection.checkedWriteStatus(
                                new WriteResponse(null, new StatusCode[] {StatusCode.GOOD}, null)));
    }
}
