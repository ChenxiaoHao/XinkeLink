package cn.xinkelink.audit;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import java.lang.reflect.Method;
import java.util.Arrays;

@Configuration(proxyBeanMethods=false) @Profile("auth")
public class AuditConfiguration {
    @Bean SmartInitializingSingleton auditCommands(ApplicationContext context,AuditRecorder recorder) {
        return ()->{
            var pointcut=new StaticMethodMatcherPointcut() {
                @Override public boolean matches(Method method,Class<?> type) {
                    return method.isAnnotationPresent(AuditAction.class);
                }
            };
            var advisor=new DefaultPointcutAdvisor(pointcut,(org.aopalliance.intercept.MethodInterceptor) call->
                    recorder.invoke(call,call.getMethod().getAnnotation(AuditAction.class)));
            for(String name:context.getBeanDefinitionNames()) {
                Class<?> type=context.getType(name);
                if(type==null||!type.getName().startsWith("cn.xinkelink."))continue;
                Class<?> target=org.springframework.util.ClassUtils.getUserClass(type);
                if(Arrays.stream(target.getMethods()).noneMatch(method->method.isAnnotationPresent(AuditAction.class)))continue;
                Object bean=context.getBean(name);
                if(!(bean instanceof Advised proxy))throw new IllegalStateException("审计管理命令必须使用事务代理");
                // 放在事务拦截器之外，捕获提交异常，且审计失败不参与业务事务。
                proxy.addAdvisor(0,advisor);
            }
        };
    }
}
