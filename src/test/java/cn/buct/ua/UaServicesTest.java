package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

class UaServicesTest {
    private static final ByteString POINT = ByteString.of(new byte[] {1});
    private static final ReferenceDescription REFERENCE =
            new ReferenceDescription(null, true, null, null, null, NodeClass.Variable, null);

    private static BrowseResult page(StatusCode status, ByteString point, int count) {
        var references = new ReferenceDescription[count];
        java.util.Arrays.fill(references, REFERENCE);
        return new BrowseResult(status, point, references);
    }

    private static final class Pages implements UaServices.BrowsePages {
        final ArrayDeque<BrowseResult> pages = new ArrayDeque<>();
        final List<ByteString> released = new ArrayList<>();
        int nextCalls;
        int requestedSize;

        Pages(BrowseResult... responses) {
            pages.addAll(List.of(responses));
        }

        public CompletableFuture<BrowseResult> first(int pageSize) {
            requestedSize = pageSize;
            return CompletableFuture.completedFuture(pages.removeFirst());
        }

        public CompletableFuture<BrowseResult> next(boolean release, ByteString point) {
            if (release) {
                released.add(point);
                return CompletableFuture.completedFuture(null);
            }
            nextCalls++;
            return CompletableFuture.completedFuture(pages.removeFirst());
        }
    }

    @Test
    void badNodeIsFailureRatherThanEmptySuccess() {
        var pages = new Pages(page(new StatusCode(StatusCodes.Bad_NodeIdUnknown), null, 0));
        var error = assertThrows(UaException.class, () -> UaServices.browse(pages, 1000, 500));
        assertEquals(StatusCodes.Bad_NodeIdUnknown, error.getStatusCode().getValue());
        assertEquals(0, pages.nextCalls);
    }

    @Test
    void mergesPagesAndAllowsExactLimitWhenComplete() throws Exception {
        var pages = new Pages(page(StatusCode.GOOD, POINT, 1), page(StatusCode.GOOD, null, 1));
        assertEquals(2, UaServices.browse(pages, 1000, 2).size());
        assertEquals(2, pages.requestedSize);
        assertEquals(1, pages.nextCalls);
        assertTrue(pages.released.isEmpty());
    }

    @Test
    void reachingLimitWithMoreResultsReleasesPointWithoutFetchingMore() {
        var pages = new Pages(page(StatusCode.GOOD, POINT, 2));
        assertThrows(IllegalStateException.class, () -> UaServices.browse(pages, 1000, 2));
        assertEquals(List.of(POINT), pages.released);
        assertEquals(0, pages.nextCalls);
    }

    @Test
    void oversizedPageAlsoReleasesItsContinuationPoint() {
        var pages = new Pages(page(StatusCode.GOOD, POINT, 3));
        assertThrows(IllegalStateException.class, () -> UaServices.browse(pages, 1000, 2));
        assertEquals(List.of(POINT), pages.released);
    }

    @Test
    void badLaterPageIsNotPartialSuccessAndReleasesNewPoint() {
        var point2 = ByteString.of(new byte[] {2});
        var pages =
                new Pages(
                        page(StatusCode.GOOD, POINT, 1),
                        page(new StatusCode(StatusCodes.Bad_UserAccessDenied), point2, 0));
        assertThrows(UaException.class, () -> UaServices.browse(pages, 1000, 5));
        assertEquals(List.of(point2), pages.released);
    }

    @Test
    void failedLaterPageWithoutNewPointStillReleasesLastKnownPoint() {
        var pages =
                new Pages(
                        page(StatusCode.GOOD, POINT, 1),
                        page(new StatusCode(StatusCodes.Bad_UserAccessDenied), null, 0));
        assertThrows(UaException.class, () -> UaServices.browse(pages, 1000, 5));
        assertEquals(List.of(POINT), pages.released);
    }

    @Test
    void zeroBudgetRejectsBeforeRequest() {
        var pages = new Pages();
        assertThrows(IllegalArgumentException.class, () -> UaServices.browse(pages, 1000, 0));
        assertEquals(0, pages.requestedSize);
    }

    @Test
    void pendingNextPageTimesOutAndReleasesKnownPoint() {
        var released = new ArrayList<ByteString>();
        var pages =
                new UaServices.BrowsePages() {
                    public CompletableFuture<BrowseResult> first(int size) {
                        return CompletableFuture.completedFuture(page(StatusCode.GOOD, POINT, 1));
                    }

                    public CompletableFuture<BrowseResult> next(boolean release, ByteString point) {
                        if (release) {
                            released.add(point);
                            return CompletableFuture.completedFuture(null);
                        }
                        return new CompletableFuture<>();
                    }
                };
        assertThrows(TimeoutException.class, () -> UaServices.browse(pages, 25, 5));
        assertEquals(List.of(POINT), released);
    }
}
