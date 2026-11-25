package cn.xinkelink.notification;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only JDBC execution/commit probe. No SQL text, bind values, content or identities are exported. */
final class NotificationSqlProbe implements BeanPostProcessor {
    static final Map<String,AtomicLong> counts=new ConcurrentHashMap<>(),nanos=new ConcurrentHashMap<>();
    static final Map<String,Long> businessCommits=new ConcurrentHashMap<>(),noticeCommits=new ConcurrentHashMap<>();
    static void record(String key,long elapsed){counts.computeIfAbsent(key,k->new AtomicLong()).incrementAndGet();nanos.computeIfAbsent(key,k->new AtomicLong()).addAndGet(elapsed);}
    static String category(){String t=Thread.currentThread().getName();return t.startsWith("http-")?"http":t.equals("notification-delivery")?"publisher":t.startsWith("ConsumeMessageThread")?"consumer":"other";}
    @Override public Object postProcessAfterInitialization(Object bean,String name){if(!(bean instanceof DataSource source)||!name.equals("dataSource")||!NotificationMqWorkerProcess.mode.equals("benchmark"))return bean;
        return new DelegatingDataSource(source){@Override public Connection getConnection()throws SQLException{return connection(super.getConnection());}@Override public Connection getConnection(String u,String p)throws SQLException{return connection(super.getConnection(u,p));}};
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    Connection connection(Connection real){Map<String,String> events=new LinkedHashMap<>();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("commit")){long start=System.nanoTime();Object result=invoke(real,method,args);long committed=System.nanoTime();record(category()+".commit",committed-start);
                events.forEach((id,kind)->(kind.equals("business")?businessCommits:noticeCommits).put(id,committed));events.clear();return result;}
            if(method.getName().equals("rollback"))events.clear();
            Object value=invoke(real,method,args);
            if(value instanceof PreparedStatement statement&&args!=null&&args.length>0&&args[0] instanceof String sql){Map<Integer,Object> binds=new HashMap<>();String normalized=sql.stripLeading().toUpperCase(Locale.ROOT);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer index)binds.put(index,a[1]);
                    if(m.getName().startsWith("execute")){long start=System.nanoTime();Object result;
                        try{result=invoke(statement,m,a);}finally{record(category()+".sql",System.nanoTime()-start);if(normalized.contains("FOR UPDATE"))record(category()+".locking.sql",System.nanoTime()-start);}
                        if((normalized.startsWith("INSERT INTO XL_NOTIFICATION_OUTBOX")||normalized.startsWith("INSERT INTO XL_NOTIFICATION_INBOX"))&&binds.get(1) instanceof String id)
                            events.put(id,normalized.startsWith("INSERT INTO XL_NOTIFICATION_OUTBOX")?"business":"notice");return result;
                    }return invoke(statement,m,a);
                });
            }return value;
        });
    }
    static Map<String,Object> snapshot(){Map<String,Object> result=new LinkedHashMap<>();var calls=new TreeMap<String,Long>();counts.forEach((k,v)->calls.put(k,v.get()));var times=new TreeMap<String,Long>();nanos.forEach((k,v)->times.put(k,v.get()));result.put("calls",calls);result.put("nanos",times);result.put("businessCommits",new TreeMap<>(businessCommits));result.put("noticeCommits",new TreeMap<>(noticeCommits));result.put("sampledAt",java.time.Instant.now().toString());return result;}
}
