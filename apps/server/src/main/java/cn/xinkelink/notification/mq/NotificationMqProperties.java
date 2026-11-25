package cn.xinkelink.notification.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xinkelink.notification.mq")
public class NotificationMqProperties {
    private volatile boolean enabled;
    private volatile boolean publisherEnabled,consumerEnabled;
    private String namesrvAddr="127.0.0.1:9876",topic="xl_interactions_dev_v1",consumerGroup="xl_notifications_dev_v1";
    private int batchSize=16,maxAttempts=12,maxReconsumeTimes=5,sendTimeoutMillis=3000;
    private long pollMillis=500,leaseMillis=60000,retryInitialMillis=1000,retryMaxMillis=60000;
    public void validate(){
        if(batchSize<1||batchSize>128||maxAttempts<1||maxAttempts>100||maxReconsumeTimes<1||maxReconsumeTimes>32||sendTimeoutMillis<100||sendTimeoutMillis>60000||pollMillis<50||pollMillis>60000||leaseMillis<sendTimeoutMillis+1000L||leaseMillis>3600000||retryInitialMillis<50||retryMaxMillis<retryInitialMillis||retryMaxMillis>86400000)
            throw new IllegalArgumentException("Invalid notification delivery bounds");
        if(!topic.matches("xl_interactions_[a-zA-Z0-9_-]+")||!consumerGroup.matches("xl_notifications_[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Explicit notification environment names required");
    }
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public boolean isPublisherEnabled(){return publisherEnabled;} public void setPublisherEnabled(boolean v){publisherEnabled=v;}
    public boolean isConsumerEnabled(){return consumerEnabled;} public void setConsumerEnabled(boolean v){consumerEnabled=v;}
    public String getNamesrvAddr(){return namesrvAddr;} public void setNamesrvAddr(String v){namesrvAddr=v;}
    public String getTopic(){return topic;} public void setTopic(String v){topic=v;}
    public String getConsumerGroup(){return consumerGroup;} public void setConsumerGroup(String v){consumerGroup=v;}
    public int getBatchSize(){return batchSize;} public void setBatchSize(int v){batchSize=v;}
    public int getMaxAttempts(){return maxAttempts;} public void setMaxAttempts(int v){maxAttempts=v;}
    public int getMaxReconsumeTimes(){return maxReconsumeTimes;} public void setMaxReconsumeTimes(int v){maxReconsumeTimes=v;}
    public int getSendTimeoutMillis(){return sendTimeoutMillis;} public void setSendTimeoutMillis(int v){sendTimeoutMillis=v;}
    public long getPollMillis(){return pollMillis;} public void setPollMillis(long v){pollMillis=v;}
    public long getLeaseMillis(){return leaseMillis;} public void setLeaseMillis(long v){leaseMillis=v;}
    public long getRetryInitialMillis(){return retryInitialMillis;} public void setRetryInitialMillis(long v){retryInitialMillis=v;}
    public long getRetryMaxMillis(){return retryMaxMillis;} public void setRetryMaxMillis(long v){retryMaxMillis=v;}
}
