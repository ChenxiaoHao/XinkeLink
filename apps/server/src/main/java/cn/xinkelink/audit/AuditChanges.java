package cn.xinkelink.audit;

/** 命令内部在持有业务锁时报告实变，防并发幂等重试产生重复成功日志。 */
public final class AuditChanges {
    private AuditChanges() {}
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    public static Scope begin() {Scope scope=new Scope(CURRENT.get());CURRENT.set(scope);return scope;}
    public static void mark(boolean changed) {if(CURRENT.get()!=null)CURRENT.get().changed=changed;}
    public static final class Scope implements AutoCloseable {
        private final Scope parent;private Boolean changed;
        private Scope(Scope parent) {this.parent=parent;}
        public Boolean changed() {return changed;}
        @Override public void close() {if(parent==null)CURRENT.remove();else CURRENT.set(parent);}
    }
}
