package cn.xinkelink.statistics;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.aop.framework.ProxyFactory;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test JVM only. Exports finite operation classes and commit timestamps, never SQL, binds or credentials. */
public final class ViewStatisticsSqlProbe implements BeanPostProcessor {
    static final Map<String,AtomicLong> counts=new ConcurrentHashMap<>(),nanos=new ConcurrentHashMap<>();
    static final Map<String,Long> accepted=new ConcurrentHashMap<>(),aggregated=new ConcurrentHashMap<>();
    static String category(){String n=Thread.currentThread().getName();return n.startsWith("http-")?"http":n.equals("post-view-publisher")?"publisher":n.equals("post-view-consumer")?"consumer":n.equals("post-view-projection")?"projection":"other";}
    static void record(String operation,long elapsed){String key=category()+"."+operation;counts.computeIfAbsent(key,k->new AtomicLong()).incrementAndGet();nanos.computeIfAbsent(key,k->new AtomicLong()).addAndGet(elapsed);}
    @Override public Object postProcessAfterInitialization(Object bean,String name){
        if(bean instanceof DataSource source&&name.equals("dataSource"))return new DelegatingDataSource(source){
            @Override public Connection getConnection()throws SQLException{return connection(super.getConnection());}
            @Override public Connection getConnection(String u,String p)throws SQLException{return connection(super.getConnection(u,p));}
        };
        if(bean instanceof StringRedisTemplate){
            ProxyFactory proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
            proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->{
                String method=call.getMethod().getName();long start=System.nanoTime();Object result;
                try{result=call.proceed();}finally{if(Set.of("execute","delete","convertAndSend").contains(method))record("redis."+method,System.nanoTime()-start);}
                if(method.equals("opsForValue")&&result instanceof ValueOperations<?,?>){
                    Object target=result;
                    return Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),new Class[]{ValueOperations.class},(p,m,a)->{long begin=System.nanoTime();try{return invoke(target,m,a);}finally{if(Set.of("get","set","multiGet","increment","setIfAbsent").contains(m.getName()))record("redis.value."+m.getName(),System.nanoTime()-begin);}});
                }
                return result;
            });return proxy.getProxy();
        }
        return bean;
    }
    Connection connection(Connection real){Map<String,String> transactionEvents=new HashMap<>();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("commit")){long begin=System.nanoTime();Object result=invoke(real,method,args);long committed=System.nanoTime();record("commit",committed-begin);
                transactionEvents.forEach((id,kind)->{Map<String,Long> target=kind.equals("receipt")?accepted:aggregated;if(target.size()<10000)target.put(id,committed);});transactionEvents.clear();return result;}
            if(method.getName().equals("rollback"))transactionEvents.clear();
            Object value=invoke(real,method,args);
            if(value instanceof PreparedStatement statement&&args!=null&&args.length>0&&args[0] instanceof String sql){String normalized=sql.stripLeading().toUpperCase(Locale.ROOT);String[] eventId={null};
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&Integer.valueOf(1).equals(a[0])&&a[1] instanceof String id&&(normalized.startsWith("INSERT INTO XL_VIEW_RECEIPT")||normalized.startsWith("INSERT INTO XL_VIEW_EVENT")))eventId[0]=id;
                    if(m.getName().startsWith("execute")){long begin=System.nanoTime();Object result;try{result=invoke(statement,m,a);}finally{record("sql",System.nanoTime()-begin);}
                        if(eventId[0]!=null)transactionEvents.put(eventId[0],normalized.startsWith("INSERT INTO XL_VIEW_RECEIPT")?"receipt":"aggregate");return result;}
                    return invoke(statement,m,a);
                });
            }
            if(value instanceof Statement statement){
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
                    if(m.getName().startsWith("execute")){long begin=System.nanoTime();try{return invoke(statement,m,a);}finally{record("sql",System.nanoTime()-begin);}}
                    return invoke(statement,m,a);
                });
            }
            return value;
        });
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    static Map<String,Object> snapshot(){Map<String,Long> c=new TreeMap<>(),t=new TreeMap<>();counts.forEach((k,v)->c.put(k,v.get()));nanos.forEach((k,v)->t.put(k,v.get()));return Map.of("calls",c,"nanos",t,"acceptedCommits",new TreeMap<>(accepted),"aggregateCommits",new TreeMap<>(aggregated),"sampledAt",Instant.now().toString());}
}
