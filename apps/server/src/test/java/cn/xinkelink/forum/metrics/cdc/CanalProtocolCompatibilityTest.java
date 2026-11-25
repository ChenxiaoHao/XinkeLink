package cn.xinkelink.forum.metrics.cdc;

import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalPacket;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Generated protocol safety and SDK wire compatibility; this is not a real Canal acceptance test. */
@Timeout(15)
class CanalProtocolCompatibilityTest {
    private static final String ERROR_PROPERTY = "com.google.protobuf.error_on_unsafe_pre22_gencode";
    private static final String UNSAFE_PROPERTY = "com.google.protobuf.use_unsafe_pre22_gencode";
    private static String previousErrorProperty;

    @BeforeAll static void requireSafeGeneratedCode() {
        assertNull(System.getProperty(UNSAFE_PROPERTY), "Unsafe old-code suppression must never make these tests pass");
        previousErrorProperty = System.getProperty(ERROR_PROPERTY);
        System.setProperty(ERROR_PROPERTY, "true");
    }
    @AfterAll static void restoreErrorProperty() {
        if (previousErrorProperty == null) System.clearProperty(ERROR_PROPERTY);
        else System.setProperty(ERROR_PROPERTY, previousErrorProperty);
    }

    @Test void protocolClassesComeFromApplicationOutput() {
        var application = PostMetricsEventMapper.class.getProtectionDomain().getCodeSource().getLocation();
        for (Class<?> generated : List.of(CanalEntry.class, CanalEntry.Column.class, CanalEntry.RowData.class,
                CanalEntry.RowChange.class, CanalEntry.Entry.class, CanalPacket.class, CanalPacket.Packet.class,
                CanalPacket.ClientAuth.class, CanalPacket.Messages.class)) {
            assertEquals(application, generated.getProtectionDomain().getCodeSource().getLocation(),
                    () -> generated.getName() + " must use regenerated application classes, not old canal.protocol gencode");
        }
    }

    @Test void regeneratedEntriesAndPacketsParseWithUnsafeOldGencodeErrorsEnabled() throws Exception {
        assertEquals("true", System.getProperty(ERROR_PROPERTY));
        var entry = postLikeEntry();
        assertEquals(entry, CanalEntry.Entry.parseFrom(entry.toByteArray()));
        var change = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
        assertEquals("target_type", change.getRowDatas(0).getAfterColumns(0).getName());
        assertEquals("post", change.getRowDatas(0).getAfterColumns(0).getValue());
        assertEquals(CanalEntry.EventType.INSERT, change.getEventType());
        var messages = CanalPacket.Messages.newBuilder().setBatchId(7).addMessages(entry.toByteString()).build();
        var packet = packet(CanalPacket.PacketType.MESSAGES, messages.toByteString());
        assertEquals(packet, CanalPacket.Packet.parseFrom(packet.toByteArray()));
        assertEquals(messages, CanalPacket.Messages.parseFrom(packet.getBody()));
    }

    @Test void officialWireFieldNumbersAndEnumsRemainCompatibleWithCanal118() {
        // The released JAR embeds a proto2 Entry descriptor even though its tag's .proto is proto3.
        // Preserve the released defaults and field presence rather than changing parser semantics.
        assertEquals(CanalEntry.EntryType.ROWDATA, CanalEntry.Entry.getDefaultInstance().getEntryType());
        assertFalse(CanalEntry.Entry.getDefaultInstance().hasEntryType());
        assertEquals(1, CanalEntry.Header.getDefaultInstance().getVersion());
        assertEquals(CanalEntry.Type.MYSQL, CanalEntry.Header.getDefaultInstance().getSourceType());
        assertEquals(CanalEntry.EventType.UPDATE, CanalEntry.Header.getDefaultInstance().getEventType());
        assertEquals(CanalEntry.EventType.UPDATE, CanalEntry.RowChange.getDefaultInstance().getEventType());
        assertFalse(CanalEntry.RowChange.getDefaultInstance().getIsDdl());
        assertFalse(CanalEntry.Column.getDefaultInstance().getIsNull());
        // The released Packet descriptor is already proto3: its unset version really is zero.
        assertEquals(0, CanalPacket.Packet.getDefaultInstance().getVersion());
        assertEquals(1, CanalEntry.Entry.getDescriptor().findFieldByName("header").getNumber());
        assertEquals(2, CanalEntry.Entry.getDescriptor().findFieldByName("entryType").getNumber());
        assertEquals(3, CanalEntry.Entry.getDescriptor().findFieldByName("storeValue").getNumber());
        assertEquals(8, CanalEntry.Header.getDescriptor().findFieldByName("schemaName").getNumber());
        assertEquals(9, CanalEntry.Header.getDescriptor().findFieldByName("tableName").getNumber());
        assertEquals(2, CanalEntry.RowChange.getDescriptor().findFieldByName("eventType").getNumber());
        assertEquals(12, CanalEntry.RowChange.getDescriptor().findFieldByName("rowDatas").getNumber());
        assertEquals(1, CanalEntry.RowData.getDescriptor().findFieldByName("beforeColumns").getNumber());
        assertEquals(2, CanalEntry.RowData.getDescriptor().findFieldByName("afterColumns").getNumber());
        assertEquals(3, CanalEntry.Column.getDescriptor().findFieldByName("name").getNumber());
        assertEquals(8, CanalEntry.Column.getDescriptor().findFieldByName("value").getNumber());
        assertEquals(3, CanalPacket.Packet.getDescriptor().findFieldByName("type").getNumber());
        assertEquals(5, CanalPacket.Packet.getDescriptor().findFieldByName("body").getNumber());
        assertEquals(3, CanalPacket.ClientAck.getDescriptor().findFieldByName("batch_id").getNumber());
        assertEquals(2, CanalEntry.EntryType.ROWDATA.getNumber());
        assertEquals(1, CanalEntry.EventType.INSERT.getNumber());
        assertEquals(7, CanalPacket.PacketType.MESSAGES.getNumber());
        assertEquals(8, CanalPacket.PacketType.CLIENTACK.getNumber());
    }

    @Test void completeDescriptorsMatchReleasedCanal118Artifact() throws Exception {
        for (var actual : List.of(CanalEntry.getDescriptor(), CanalPacket.getDescriptor())) {
            String name = actual.getName().substring(actual.getName().lastIndexOf('/') + 1);
            try (var input = getClass().getResourceAsStream("/canal/" + name + ".descriptor.pb")) {
                assertNotNull(input, "The released artifact's original descriptor must be retained");
                var original = DescriptorProtos.FileDescriptorProto.parseFrom(input);
                assertEquals(canonicalDescriptor(original), canonicalDescriptor(actual.toProto()),
                        "Protocol fields, types, presence, defaults, enums and options must match the released SDK");
            }
        }
    }

    @Test void nativeCanal118ClientUsesRegeneratedPacketsForHandshakeFetchAndAck() throws Exception {
        // A small loopback peer exercises the SDK's precompiled binary calls against the new FQNs.
        // The task's real server/binlog acceptance remains a separate test and must not be inferred here.
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var worker = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5000);
            Future<Void> peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    var handshake = CanalPacket.Handshake.newBuilder().setCommunicationEncoding("UTF-8")
                            .setSeeds(ByteString.copyFromUtf8("0123456789abcdef0123"))
                            .setSupportedCompressions(CanalPacket.Compression.NONE).build();
                    write(output, packet(CanalPacket.PacketType.HANDSHAKE, handshake.toByteString()));
                    var auth = read(input);
                    assertEquals(CanalPacket.PacketType.CLIENTAUTHENTICATION, auth.getType());
                    assertEquals("", CanalPacket.ClientAuth.parseFrom(auth.getBody()).getUsername());
                    write(output, packet(CanalPacket.PacketType.ACK, CanalPacket.Ack.newBuilder().setErrorCode(0).build().toByteString()));
                    // The official connector rolls back on connect before it subscribes. Client identity
                    // belongs to these commands, rather than its credential-only authentication packet.
                    var rollback = read(input);
                    assertEquals(CanalPacket.PacketType.CLIENTROLLBACK, rollback.getType());
                    var rollbackBody = CanalPacket.ClientRollback.parseFrom(rollback.getBody());
                    assertEquals("1001", rollbackBody.getClientId());
                    assertEquals(0, rollbackBody.getBatchId());
                    var subscription = read(input);
                    assertEquals(CanalPacket.PacketType.SUBSCRIPTION, subscription.getType());
                    assertEquals("1001", CanalPacket.Sub.parseFrom(subscription.getBody()).getClientId());
                    write(output, packet(CanalPacket.PacketType.ACK, CanalPacket.Ack.newBuilder().setErrorCode(0).build().toByteString()));
                    var get = read(input);
                    assertEquals(CanalPacket.PacketType.GET, get.getType());
                    assertFalse(CanalPacket.Get.parseFrom(get.getBody()).getAutoAck());
                    var messages = CanalPacket.Messages.newBuilder().setBatchId(7).addMessages(postLikeEntry().toByteString()).build();
                    write(output, packet(CanalPacket.PacketType.MESSAGES, messages.toByteString()));
                    var ack = read(input);
                    assertEquals(CanalPacket.PacketType.CLIENTACK, ack.getType());
                    assertEquals(7, CanalPacket.ClientAck.parseFrom(ack.getBody()).getBatchId());
                    // The native ACK is one-way. No fake acknowledgement reply is invented here.
                    return null;
                }
            });
            var connector = CanalConnectors.newSingleConnector(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    "protocol-compatibility", "", "");
            try {
                connector.connect(); connector.subscribe("xinkelink_protocol_test\\.xl_like");
                var message = connector.getWithoutAck(10, 2L, TimeUnit.SECONDS);
                assertEquals(7, message.getId());
                var entries = message.isRaw()
                        ? message.getRawEntries().stream().map(bytes -> {
                            try { return CanalEntry.Entry.parseFrom(bytes); }
                            catch (com.google.protobuf.InvalidProtocolBufferException error) { throw new AssertionError(error); }
                        }).toList() : message.getEntries();
                assertEquals(List.of(postLikeEntry()), entries);
                connector.ack(message.getId());
                peer.get(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                // Preserve the peer assertion when its close also causes a client-side EOF.
                if (peer.isDone()) {
                    try { peer.get(); }
                    catch (ExecutionException peerError) { error.addSuppressed(peerError.getCause()); }
                }
                throw error;
            } finally { connector.disconnect(); }
        }
    }

    private static CanalEntry.Entry postLikeEntry() {
        var row = CanalEntry.RowData.newBuilder()
                .addAfterColumns(CanalEntry.Column.newBuilder().setName("target_type").setValue("post").setIsNull(false))
                .addAfterColumns(CanalEntry.Column.newBuilder().setName("target_id").setValue("17").setIsNull(false));
        var change = CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT).addRowDatas(row).build();
        return CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName("xinkelink_protocol_test").setTableName("xl_like")
                        .setEventType(CanalEntry.EventType.INSERT).setExecuteTime(1234))
                .setStoreValue(change.toByteString()).build();
    }
    private static DescriptorProtos.FileDescriptorProto canonicalDescriptor(DescriptorProtos.FileDescriptorProto file) {
        // Only build input path, implicit proto2 syntax spelling and protoc's JSON-name metadata differ.
        // Defaults, oneofs, labels, enum values and all options remain part of the comparison.
        var result = file.toBuilder().clearName().clearSourceCodeInfo()
                .setSyntax(file.getSyntax().isEmpty() ? "proto2" : file.getSyntax()).clearMessageType();
        file.getMessageTypeList().forEach(message -> result.addMessageType(withoutJsonNames(message)));
        return result.build();
    }
    private static DescriptorProtos.DescriptorProto withoutJsonNames(DescriptorProtos.DescriptorProto message) {
        var result = message.toBuilder().clearField().clearNestedType();
        message.getFieldList().forEach(field -> result.addField(field.toBuilder().clearJsonName()));
        message.getNestedTypeList().forEach(nested -> result.addNestedType(withoutJsonNames(nested)));
        return result.build();
    }
    private static CanalPacket.Packet packet(CanalPacket.PacketType type, ByteString body) {
        return CanalPacket.Packet.newBuilder().setMagicNumber(17).setVersion(1)
                .setCompression(CanalPacket.Compression.NONE).setType(type).setBody(body).build();
    }
    private static void write(DataOutputStream output, CanalPacket.Packet packet) throws IOException {
        var bytes = packet.toByteArray(); output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private static CanalPacket.Packet read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 65536) throw new IOException("Unexpected compatibility-test packet size");
        var bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete compatibility-test packet");
        return CanalPacket.Packet.parseFrom(bytes);
    }
}
