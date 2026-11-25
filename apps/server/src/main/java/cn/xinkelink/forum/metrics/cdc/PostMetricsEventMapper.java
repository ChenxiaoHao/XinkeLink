package cn.xinkelink.forum.metrics.cdc;

import com.alibaba.otter.canal.protocol.CanalEntry;

import java.util.*;

/** Maps only public-count dependencies; row content never escapes this class. */
public final class PostMetricsEventMapper {
    private static final Set<String> TABLES = Set.of("xl_like", "xl_comment", "xl_post");
    private static final Set<String> MARKERS = Set.of("TRANSACTIONBEGIN", "TRANSACTIONEND", "HEARTBEAT", "GTIDLOG");
    private static final Set<String> POST_UPDATE = Set.of("id", "status", "review_state", "deleted_at");
    private static final Set<String> COMMENT_UPDATE = Set.of("target_type", "target_id", "root_id", "status", "deleted_at");
    private final String schema;

    public record Mapping(Set<Long> postIds, long relevantEvents, long maxExecuteTime) {
        public Mapping { postIds = Collections.unmodifiableSet(new LinkedHashSet<>(postIds)); }
    }

    /** Finite, payload-free reasons are safe for controlled consumer diagnostics. */
    public enum Failure { INVALID_ENTRY, MALFORMED_ROW_CHANGE, UNSUPPORTED_RELATED_EVENT, MISSING_REQUIRED_COLUMNS, INVALID_ROW_VALUE }
    public static final class MappingException extends RuntimeException {
        private final Failure reason;
        private MappingException(Failure reason) { super("CDC mapping failed: " + reason.name()); this.reason = reason; }
        public Failure reason() { return reason; }
    }

    public PostMetricsEventMapper(String schema) {
        if (schema == null || !schema.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid CDC schema");
        this.schema = schema;
    }

    /** Canal delivery/commit and acknowledgement are the consumer's responsibility.
     * This mapper either returns the entire batch's dependencies or throws; it never drops a bad relevant row. */
    public Mapping map(List<CanalEntry.Entry> entries) {
        if (entries == null) throw failure(Failure.INVALID_ENTRY);
        Set<Long> posts = new LinkedHashSet<>();
        long relevant = 0, latest = 0;
        for (var entry : entries) {
            if (entry == null || !entry.hasEntryType()) throw failure(Failure.INVALID_ENTRY);
            if (MARKERS.contains(entry.getEntryType().name())) continue;
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA || !entry.hasHeader()) throw failure(Failure.INVALID_ENTRY);
            var header = entry.getHeader();
            if (header.getSchemaName().isEmpty()) throw failure(Failure.INVALID_ENTRY);
            if (!schema.equals(header.getSchemaName())) continue;
            String table = header.getTableName();
            if (table.isEmpty()) throw failure(Failure.INVALID_ENTRY);
            if (!TABLES.contains(table)) continue;

            CanalEntry.RowChange change;
            try { change = CanalEntry.RowChange.parseFrom(entry.getStoreValue()); }
            catch (com.google.protobuf.InvalidProtocolBufferException ignored) {
                // Parser exceptions can contain raw values. Do not attach their messages/causes to consumer logs.
                throw failure(Failure.MALFORMED_ROW_CHANGE);
            }
            if (!change.hasEventType() || change.getIsDdl()
                    || (header.hasEventType() && header.getEventType() != change.getEventType()))
                throw failure(Failure.UNSUPPORTED_RELATED_EVENT);
            var event = change.getEventType();
            if (event != CanalEntry.EventType.INSERT && event != CanalEntry.EventType.DELETE && event != CanalEntry.EventType.UPDATE)
                throw failure(Failure.UNSUPPORTED_RELATED_EVENT);
            if (change.getRowDatasCount() == 0) throw failure(Failure.MISSING_REQUIRED_COLUMNS);

            for (var row : change.getRowDatasList()) {
                Set<Long> affected = rowTargets(table, event, row);
                if (!affected.isEmpty()) {
                    posts.addAll(affected); relevant++;
                    latest = Math.max(latest, header.getExecuteTime());
                }
            }
        }
        return new Mapping(posts, relevant, latest);
    }

    private static Set<Long> rowTargets(String table, CanalEntry.EventType event, CanalEntry.RowData row) {
        // DELETE has no usable after image; UPDATE needs both images, not merely updated-column flags.
        if (event == CanalEntry.EventType.INSERT) return insertedOrDeleted(table, image(row.getAfterColumnsList()));
        if (event == CanalEntry.EventType.DELETE) return insertedOrDeleted(table, image(row.getBeforeColumnsList()));
        Map<String, String> before = image(row.getBeforeColumnsList());
        Map<String, String> after = image(row.getAfterColumnsList());
        Set<Long> result = new LinkedHashSet<>();
        if (table.equals("xl_like")) {
            Long oldPost = likeTarget(before), newPost = likeTarget(after);
            if (!Objects.equals(oldPost, newPost)) { add(result, oldPost); add(result, newPost); }
        } else if (table.equals("xl_comment")) {
            long oldPost = commentTarget(before), newPost = commentTarget(after);
            requireShape(before, COMMENT_UPDATE); requireShape(after, COMMENT_UPDATE);
            commentState(before); commentState(after);
            if (different(before, after, COMMENT_UPDATE)) { result.add(oldPost); result.add(newPost); }
        } else {
            long oldPost = positiveId(required(before, "id")), newPost = positiveId(required(after, "id"));
            requireShape(before, POST_UPDATE); requireShape(after, POST_UPDATE);
            postState(before); postState(after);
            if (different(before, after, POST_UPDATE)) { result.add(oldPost); result.add(newPost); }
        }
        return result;
    }

    private static Set<Long> insertedOrDeleted(String table, Map<String, String> columns) {
        Long post = switch (table) {
            case "xl_like" -> likeTarget(columns);
            case "xl_comment" -> commentTarget(columns);
            default -> positiveId(required(columns, "id"));
        };
        return post == null ? Set.of() : Set.of(post);
    }

    private static Long likeTarget(Map<String, String> columns) {
        String type = required(columns, "target_type");
        if (type.equals("comment")) return null; // Known unrelated type: do not interpret comment IDs as post IDs.
        if (!type.equals("post")) throw failure(Failure.INVALID_ROW_VALUE);
        return positiveId(required(columns, "target_id"));
    }

    private static long commentTarget(Map<String, String> columns) {
        // V9 removed product comments and now constrains this column to post only.
        if (!required(columns, "target_type").equals("post")) throw failure(Failure.INVALID_ROW_VALUE);
        return positiveId(required(columns, "target_id"));
    }

    private static void commentState(Map<String, String> columns) {
        allowed(required(columns, "status"), Set.of("visible", "hidden"));
        String root = columns.get("root_id");
        if (root != null) positiveId(root);
    }
    private static void postState(Map<String, String> columns) {
        allowed(required(columns, "status"), Set.of("visible", "hidden"));
        allowed(required(columns, "review_state"), Set.of("normal", "pending"));
    }
    private static void allowed(String value, Set<String> values) {
        if (!values.contains(value)) throw failure(Failure.INVALID_ROW_VALUE);
    }
    private static boolean different(Map<String, String> before, Map<String, String> after, Set<String> fields) {
        return fields.stream().anyMatch(field -> !Objects.equals(before.get(field), after.get(field)));
    }
    private static Map<String, String> image(List<CanalEntry.Column> columns) {
        Map<String, String> result = new HashMap<>();
        for (var column : columns) {
            String name = column.getName();
            if (name.isEmpty() || result.containsKey(name)) throw failure(Failure.INVALID_ROW_VALUE);
            result.put(name, column.getIsNull() ? null : column.getValue());
        }
        return result;
    }
    private static void requireShape(Map<String, String> columns, Set<String> fields) {
        if (!columns.keySet().containsAll(fields)) throw failure(Failure.MISSING_REQUIRED_COLUMNS);
    }
    private static String required(Map<String, String> columns, String field) {
        if (!columns.containsKey(field)) throw failure(Failure.MISSING_REQUIRED_COLUMNS);
        String value = columns.get(field);
        if (value == null) throw failure(Failure.INVALID_ROW_VALUE);
        return value;
    }
    private static long positiveId(String value) {
        if (!value.matches("[0-9]{1,19}")) throw failure(Failure.INVALID_ROW_VALUE);
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw failure(Failure.INVALID_ROW_VALUE);
    }
    private static void add(Set<Long> posts, Long post) { if (post != null) posts.add(post); }
    private static MappingException failure(Failure reason) { return new MappingException(reason); }
}
