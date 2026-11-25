package cn.xinkelink.forum.metrics.cdc;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PostMetricsEventMapperTest {
    private static final String SCHEMA = "xinkelink_cdc_test";
    private final PostMetricsEventMapper mapper = new PostMetricsEventMapper(SCHEMA);

    @Test void postLikeInsertUsesAfterTarget() {
        var mapping = mapper.map(List.of(change("xl_like", CanalEntry.EventType.INSERT, Map.of(), like("post", "17"))));
        assertEquals(Set.of(17L), mapping.postIds());
        assertEquals(1, mapping.relevantEvents());
        assertEquals(1234, mapping.maxExecuteTime());
    }

    @Test void physicalLikeDeleteUsesBeforeTargetRatherThanAbsentAfter() {
        assertEquals(Set.of(17L), ids(change("xl_like", CanalEntry.EventType.DELETE, like("post", "17"), Map.of())));
    }

    @Test void likeUpdateInvalidatesBothOldAndNewPostTargets() {
        assertEquals(Set.of(17L, 18L), ids(change("xl_like", CanalEntry.EventType.UPDATE,
                like("post", "17"), like("post", "18"))));
    }

    @Test void transitionsBetweenPostAndCommentLikesOnlyInvalidatePostSide() {
        assertEquals(Set.of(17L), ids(change("xl_like", CanalEntry.EventType.UPDATE,
                like("post", "17"), Map.of("target_type", "comment"))));
        assertEquals(Set.of(18L), ids(change("xl_like", CanalEntry.EventType.UPDATE,
                Map.of("target_type", "comment"), like("post", "18"))));
    }

    @Test void commentLikesAreUnrelatedAndNeverTreatedAsPostIds() {
        for (var event : List.of(CanalEntry.EventType.INSERT, CanalEntry.EventType.DELETE, CanalEntry.EventType.UPDATE)) {
            assertTrue(ids(change("xl_like", event, Map.of("target_type", "comment"),
                    Map.of("target_type", "comment"))).isEmpty());
        }
    }

    @Test void unchangedLikeTargetDoesNotInvalidateOnUserOrTimestampChanges() {
        var after = new HashMap<>(like("post", "17")); after.put("user_id", "999"); after.put("created_at", "later");
        assertTrue(ids(change("xl_like", CanalEntry.EventType.UPDATE, like("post", "17"), after)).isEmpty());
    }

    @Test void rootAndReplyInsertAndPhysicalDeleteUseTheirOwnPostTarget() {
        for (String root : Arrays.asList(null, "91")) {
            var row = comment("17", root, "visible", null);
            assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.INSERT, Map.of(), row)));
            assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.DELETE, row, Map.of())));
        }
    }

    @Test void rootHideRestoreSoftDeleteAndReplyChangesInvalidateWholePostWithoutLookingUpChildren() {
        var visible = comment("17", null, "visible", null);
        var hidden = comment("17", null, "hidden", null);
        var deleted = comment("17", null, "visible", "2026-11-16 01:02:03.000");
        assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.UPDATE, visible, hidden)));
        assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.UPDATE, hidden, visible)));
        assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.UPDATE, visible, deleted)));
        assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.UPDATE,
                comment("17", "91", "visible", null), comment("17", "91", "hidden", null))));
    }

    @Test void commentTargetAndRootChangesInvalidateTheirPostDependencies() {
        assertEquals(Set.of(17L, 18L), ids(change("xl_comment", CanalEntry.EventType.UPDATE,
                comment("17", null, "visible", null), comment("18", "91", "visible", null))));
        assertEquals(Set.of(17L), ids(change("xl_comment", CanalEntry.EventType.UPDATE,
                comment("17", "91", "visible", null), comment("17", "92", "visible", null))));
    }

    @Test void commentContentOnlyUpdateDoesNotInvalidateCounts() {
        var before = comment("17", null, "visible", null);
        var after = new HashMap<>(before); after.put("content", "private text must never be returned"); after.put("version", "2");
        assertTrue(ids(change("xl_comment", CanalEntry.EventType.UPDATE, before, after)).isEmpty());
    }

    @Test void postVisibilityReviewAndSoftDeleteChangesInvalidatePostId() {
        var before = post("17", "visible", "normal", null);
        for (var after : List.of(post("17", "hidden", "normal", null), post("17", "visible", "pending", null),
                post("17", "visible", "normal", "2026-11-16 01:02:03.000"))) {
            assertEquals(Set.of(17L), ids(change("xl_post", CanalEntry.EventType.UPDATE, before, after)));
            assertEquals(Set.of(17L), ids(change("xl_post", CanalEntry.EventType.UPDATE, after, before)));
        }
    }

    @Test void postInsertDeleteAndPrimaryKeyChangeUseBeforeAndAfterIds() {
        assertEquals(Set.of(17L), ids(change("xl_post", CanalEntry.EventType.INSERT, Map.of(), Map.of("id", "17"))));
        assertEquals(Set.of(17L), ids(change("xl_post", CanalEntry.EventType.DELETE, Map.of("id", "17"), Map.of())));
        assertEquals(Set.of(17L, 18L), ids(change("xl_post", CanalEntry.EventType.UPDATE,
                post("17", "visible", "normal", null), post("18", "visible", "normal", null))));
    }

    @Test void postContentCategoryAuthorAndAnonymousFieldsStayOutsideCacheScope() {
        var before = post("17", "visible", "normal", null);
        var after = new HashMap<>(before);
        after.put("content", "private-content"); after.put("category", "buddy");
        after.put("author_id", "900"); after.put("is_anonymous", "1");
        assertTrue(ids(change("xl_post", CanalEntry.EventType.UPDATE, before, after)).isEmpty());
    }

    @Test void batchDeduplicatesPostsButCountsEveryRelevantRow() {
        var row = CanalEntry.RowData.newBuilder().addAllAfterColumns(columns(like("post", "17"))).build();
        var multi = entry(SCHEMA, "xl_like", CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT)
                .addRowDatas(row).addRowDatas(row).build(), 1000);
        var later = entry(SCHEMA, "xl_comment", CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT)
                .addRowDatas(CanalEntry.RowData.newBuilder().addAllAfterColumns(columns(Map.of("target_type", "post", "target_id", "17")))).build(), 2000);
        var mapping = mapper.map(List.of(multi, later));
        assertEquals(Set.of(17L), mapping.postIds()); assertEquals(3, mapping.relevantEvents()); assertEquals(2000, mapping.maxExecuteTime());
        assertThrows(UnsupportedOperationException.class, () -> mapping.postIds().add(18L));
    }

    @Test void otherSchemaAndUnrelatedTablesAreIgnoredWithoutParsingTheirPayloads() {
        var bad = CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setStoreValue(ByteString.copyFromUtf8("bad-protobuf"));
        assertTrue(mapper.map(List.of(bad.setHeader(CanalEntry.Header.newBuilder().setSchemaName("other_schema").setTableName("xl_post")).build(),
                bad.setHeader(CanalEntry.Header.newBuilder().setSchemaName(SCHEMA).setTableName("xl_message")).build())).postIds().isEmpty());
    }

    @Test void transactionMarkersHeartbeatAndGtidMarkersDoNotBecomeBusinessEvents() {
        List<CanalEntry.Entry> markers = new ArrayList<>();
        for (var type : CanalEntry.EntryType.values()) {
            if (Set.of("TRANSACTIONBEGIN", "TRANSACTIONEND", "HEARTBEAT", "GTIDLOG").contains(type.name()))
                markers.add(CanalEntry.Entry.newBuilder().setEntryType(type).build());
        }
        var mapping = mapper.map(markers);
        assertTrue(mapping.postIds().isEmpty()); assertEquals(0, mapping.relevantEvents()); assertEquals(0, mapping.maxExecuteTime());
    }

    @Test void malformedRelatedPayloadIsNotSilentlyAcknowledged() {
        var entry = CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName(SCHEMA).setTableName("xl_post"))
                .setStoreValue(ByteString.copyFromUtf8("private-content token-secret not protobuf")).build();
        var failure = assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry)));
        assertFalse(failure.getMessage().contains("private-content")); assertFalse(failure.getMessage().contains("token-secret"));
        assertNull(failure.getCause(), "Raw parser messages must not leak row bytes into caller logs");
    }

    @Test void relevantDdlAndUnsupportedRowEventsFailClosedWhileUnrelatedDdlIsIgnored() {
        var ddl = CanalEntry.RowChange.newBuilder().setIsDdl(true).setEventType(CanalEntry.EventType.ALTER).setSql("private SQL").build();
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry(SCHEMA, "xl_post", ddl, 1000))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry(SCHEMA, "", ddl, 1000))));
        assertTrue(mapper.map(List.of(entry(SCHEMA, "xl_message", ddl, 1000))).postIds().isEmpty());
        var truncate = CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.TRUNCATE).build();
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry(SCHEMA, "xl_comment", truncate, 1000))));
    }

    @Test void deleteWithoutBeforeAndUpdateWithPartialImagesFailClosed() {
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_like", CanalEntry.EventType.DELETE,
                Map.of(), like("post", "17"))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_comment", CanalEntry.EventType.UPDATE,
                Map.of("target_type", "post", "target_id", "17"), comment("17", null, "hidden", null))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_post", CanalEntry.EventType.UPDATE,
                Map.of("id", "17"), post("17", "hidden", "normal", null))));
    }

    @Test void missingTypeUnknownTypeAndInvalidPositiveLongIdsFailClosed() {
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_like", CanalEntry.EventType.INSERT, Map.of(), Map.of("target_id", "17"))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_like", CanalEntry.EventType.INSERT, Map.of(), like("product", "17"))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_comment", CanalEntry.EventType.INSERT, Map.of(), like("comment", "17"))));
        for (String invalid : List.of("0", "-1", "+1", "1.0", "9223372036854775808", "token-secret")) {
            var failure = assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_post", CanalEntry.EventType.INSERT, Map.of(), Map.of("id", invalid))));
            assertFalse(failure.getMessage().contains(invalid));
        }
        assertEquals(Set.of(Long.MAX_VALUE), ids(change("xl_post", CanalEntry.EventType.INSERT, Map.of(), Map.of("id", Long.toString(Long.MAX_VALUE)))));
    }

    @Test void nullOrDuplicateRequiredColumnsAndImpossibleStatesFailClosed() {
        var nullId = new HashMap<String, String>(); nullId.put("id", null);
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_post", CanalEntry.EventType.INSERT, Map.of(), nullId)));
        var duplicate = CanalEntry.RowData.newBuilder().addAfterColumns(column("id", "17")).addAfterColumns(column("id", "18")).build();
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry(SCHEMA, "xl_post",
                CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT).addRowDatas(duplicate).build(), 1000))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_comment", CanalEntry.EventType.UPDATE,
                comment("17", null, "visible", null), comment("17", null, "unknown", null))));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> ids(change("xl_post", CanalEntry.EventType.UPDATE,
                post("17", "visible", "normal", null), post("17", "visible", "unknown", null))));
    }

    @Test void invalidSchemaNullBatchAndMissingEntryHeaderCannotBeMisclassifiedAsUnrelated() {
        for (String invalid : List.of("", " ", "db.*", "db-name", "db;secret"))
            assertThrows(IllegalArgumentException.class, () -> new PostMetricsEventMapper(invalid));
        assertThrows(IllegalArgumentException.class, () -> new PostMetricsEventMapper(null));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(null));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(Collections.singletonList(null)));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(CanalEntry.Entry.newBuilder().build())));
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA).build())));
        assertTrue(mapper.map(List.of()).postIds().isEmpty());
    }

    @Test void relatedDmlWithNoRowsIsRejectedInsteadOfAcknowledgedAsNoop() {
        var empty = CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT).build();
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry(SCHEMA, "xl_post", empty, 1000))));
    }

    @Test void missingOrConflictingEventTypesCannotBeAcknowledgedAsAnAssumedUpdate() {
        var row = CanalEntry.RowData.newBuilder().addAllAfterColumns(columns(Map.of("id", "17"))).build();
        var missingType = CanalEntry.RowChange.newBuilder().addRowDatas(row).build();
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(entry(SCHEMA, "xl_post", missingType, 1000))));
        var normal = change("xl_post", CanalEntry.EventType.INSERT, Map.of(), Map.of("id", "17"));
        var conflicting = normal.toBuilder().setHeader(normal.getHeader().toBuilder().setEventType(CanalEntry.EventType.DELETE)).build();
        assertThrows(PostMetricsEventMapper.MappingException.class, () -> mapper.map(List.of(conflicting)));
    }

    private Set<Long> ids(CanalEntry.Entry entry) { return mapper.map(List.of(entry)).postIds(); }
    private static CanalEntry.Entry change(String table, CanalEntry.EventType event, Map<String, String> before, Map<String, String> after) {
        var row = CanalEntry.RowData.newBuilder().addAllBeforeColumns(columns(before)).addAllAfterColumns(columns(after));
        return entry(SCHEMA, table, CanalEntry.RowChange.newBuilder().setEventType(event).addRowDatas(row).build(), 1234);
    }
    private static CanalEntry.Entry entry(String schema, String table, CanalEntry.RowChange change, long time) {
        return CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName(schema).setTableName(table).setExecuteTime(time))
                .setStoreValue(change.toByteString()).build();
    }
    private static Map<String, String> like(String type, String target) { return Map.of("target_type", type, "target_id", target); }
    private static Map<String, String> comment(String target, String root, String status, String deleted) {
        var row = new LinkedHashMap<String, String>(); row.put("target_type", "post"); row.put("target_id", target);
        row.put("root_id", root); row.put("status", status); row.put("deleted_at", deleted); return row;
    }
    private static Map<String, String> post(String id, String status, String review, String deleted) {
        var row = new LinkedHashMap<String, String>(); row.put("id", id); row.put("status", status); row.put("review_state", review); row.put("deleted_at", deleted); return row;
    }
    private static List<CanalEntry.Column> columns(Map<String, String> values) { return values.entrySet().stream().map(e -> column(e.getKey(), e.getValue())).toList(); }
    private static CanalEntry.Column column(String name, String value) {
        return CanalEntry.Column.newBuilder().setName(name).setIsNull(value == null).setValue(value == null ? "" : value).build();
    }
}
