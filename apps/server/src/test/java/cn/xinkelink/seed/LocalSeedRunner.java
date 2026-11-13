package cn.xinkelink.seed;

import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 本地开发工具，无Spring组件、HTTP入口或登录适配器，不打入生产Jar。 */
public final class LocalSeedRunner {
    private static final Set<String> MODES = Set.of("--plan", "--apply", "--verify", "--rollback-test");
    record Options(String mode, Path images, Path report, String dataset) {}

    static Options parse(String[] args) {
        if ((args.length != 5 && args.length != 7) || !MODES.contains(args[0]) || !args[1].equals("--images") || !args[3].equals("--report"))
            throw new IllegalArgumentException("请指定唯一执行模式、图片目录与报告路径");
        String dataset=args.length==7?args[6]:"preview";
        if((args.length==7&&!args[5].equals("--dataset"))||!Set.of("preview","stress").contains(dataset))throw new IllegalArgumentException("数据集参数无效");
        return new Options(args[0], Path.of(args[2]).toAbsolutePath().normalize(), Path.of(args[4]).toAbsolutePath().normalize(),dataset);
    }
    static void checkEnvironment(String value) {
        if (!"local".equals(value) && !"test".equals(value)) throw new IllegalArgumentException("仅允许local/test环境");
    }
    public static void main(String[] args) throws Exception {
        var options = parse(args);
        boolean preview=options.dataset().equals("preview");
        String namespace=preview?LocalPreviewData.NAMESPACE:LocalSeedData.NAMESPACE;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("namespace", namespace);
        report.put("dataset",options.dataset());
        report.put("mode", options.mode());
        report.put("committed", false);
        if (options.mode().equals("--plan")) {
            report.put("expected", preview?LocalPreviewData.plan():LocalSeedData.plan());
            report.put("databaseConnected", false);
        } else {
            checkEnvironment(System.getenv("XINKELINK_SEED_ENV"));
            String url = System.getenv("XINKELINK_DB_URL");
            LocalSeedData.checkDatabase(url);
            boolean committed = false;
            try (Connection connection = DriverManager.getConnection(url, System.getenv("XINKELINK_DB_USERNAME"), System.getenv("XINKELINK_DB_PASSWORD"))) {
                String database = connection.getCatalog();
                if (database == null || !database.matches("[a-zA-Z0-9_]+_(dev|test)")) throw new IllegalArgumentException("实际数据库不属于开发/测试库");
                // 同一批次串行执行，避免两个生成进程争抢幂等键。
                try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                    statement.setString(1, "xinkelink:local-data-tools");
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("另一数据脚本正在运行");
                    }
                }
                connection.setReadOnly(options.mode().equals("--verify"));
                connection.setAutoCommit(false);
                try {
                    var data = preview?(options.mode().equals("--verify")?LocalPreviewData.verify(connection,options.images()):LocalPreviewData.populate(connection,options.images(),namespace,LocalSeedData.NAMESPACE))
                            :(options.mode().equals("--verify")?LocalSeedData.verify(connection, options.images(),namespace):LocalSeedData.populate(connection, options.images(),namespace));
                    report.putAll(data);
                    String contact=System.getenv("XINKELINK_SEED_CONTACT_USER_ID");
                    if(contact!=null&&!contact.isBlank()&&!options.mode().equals("--verify")) {
                        if(!preview||!contact.matches("[1-9][0-9]{0,17}"))throw new IllegalArgumentException("联系账号参数无效");
                        report.put("contacts",LocalPreviewExtras.apply(connection,options.images(),namespace,"day5-test-adapter",Long.parseLong(contact)));
                    }
                    if (options.mode().equals("--apply")) { connection.commit(); committed = true; }
                    else connection.rollback();
                    report.put("committed", committed);
                    report.put("databaseConnected", true);
                    report.put("rolledBack", options.mode().equals("--rollback-test"));
                } catch (Exception failure) { connection.rollback(); throw failure; }
            } catch (Exception failure) {
                // 不打印JDBC异常正文或SQL参数，防止凭据及业务字段进入控制台。
                System.err.println("本地数据工具失败：" + failure.getClass().getSimpleName() + "；committed=" + committed);
                System.exit(1);
                return;
            }
        }
        Files.createDirectories(options.report().getParent());
        Files.writeString(options.report(), JsonMapper.builder().build().writeValueAsString(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(options.mode().equals("--plan") ? "计划已生成，未连接数据库。" : "数据数量及关联核验通过；committed=" + report.get("committed"));
    }
}
