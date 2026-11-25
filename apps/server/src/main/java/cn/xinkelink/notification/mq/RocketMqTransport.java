package cn.xinkelink.notification.mq;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Native remoting SDK; no Boot starter, Proxy, half-transaction messages or dependency-driven Boot version changes. */
public class RocketMqTransport implements OutboxPublisher.Sender,AutoCloseable {
    private final NotificationMqProperties config;private DefaultMQProducer producer;private boolean closed;
    public RocketMqTransport(NotificationMqProperties config){this.config=config;}
    private synchronized DefaultMQProducer producer()throws Exception{
        if(closed)throw new IllegalStateException("Notification transport stopped");
        if(producer==null){var next=createProducer();
            next.setNamesrvAddr(config.getNamesrvAddr());next.setInstanceName("notice-producer-"+UUID.randomUUID());
            next.setRetryTimesWhenSendFailed(0);next.setSendMsgTimeout(config.getSendTimeoutMillis());next.setVipChannelEnabled(false);
            try{next.start();producer=next;}catch(Exception e){next.shutdown();throw e;}}
        return producer;
    }
    protected DefaultMQProducer createProducer(){return new DefaultMQProducer("xl_notice_publish_"+UUID.randomUUID());}
    /** Only the lifecycle owner calls this after its previous executor has fully terminated. */
    public synchronized void open(){closed=false;}
    @Override public OutboxPublisher.Receipt send(InteractionJournal.Claim claim)throws Exception{
        Message message=new Message(config.getTopic(),"intent_v1",claim.eventId(),claim.payload().getBytes(StandardCharsets.UTF_8));
        message.setWaitStoreMsgOK(true);
        var result=producer().send(message,config.getSendTimeoutMillis());
        // SYNC_FLUSH is an operational requirement; flush timeout/slave errors are explicitly not success.
        return new OutboxPublisher.Receipt(result.getSendStatus()==SendStatus.SEND_OK?"OK":"NOT_STORED",result.getMsgId());
    }
    @Override public synchronized void close(){closed=true;if(producer!=null){producer.shutdown();producer=null;}}
}
