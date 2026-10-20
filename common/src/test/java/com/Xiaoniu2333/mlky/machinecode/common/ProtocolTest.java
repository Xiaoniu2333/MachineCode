package com.Xiaoniu2333.mlky.machinecode.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    private static final String CODE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void roundTripsClientReport() {
        final Protocol.ClientReport report = new Protocol.ClientReport(
                CODE, Platform.LINUX, "Linux", "6.8.0", "amd64", "1.0.0");
        final Protocol.ClientReport decoded = Protocol.decodeClientReport(report.encode());

        assertEquals(report, decoded);
    }

    @Test
    void roundTripsForwardedReportAndKeepsProxyUuid() {
        final UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        final Protocol.ClientReport report = new Protocol.ClientReport(
                CODE, Platform.WINDOWS, "Windows 11", "10.0", "amd64", "1.0.0");
        final Protocol.ForwardedReport decoded =
                Protocol.decodeForwarded(Protocol.frameForBackend(uuid, report.encode()));

        assertEquals(uuid, decoded.playerId());
        assertEquals(report, decoded.report());
    }

    @Test
    void rejectsWrongVersion() {
        final byte[] bytes = new Protocol.ClientReport(
                CODE, Platform.WINDOWS, "Windows", "10", "amd64", "1").encode();
        bytes[0] = 9;

        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.decodeClientReport(bytes));
    }

    @Test
    void rejectsTruncatedAndOversizedInputs() {
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.decodeClientReport(new byte[0]));
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.decodeForwarded(new byte[16]));
    }
}
