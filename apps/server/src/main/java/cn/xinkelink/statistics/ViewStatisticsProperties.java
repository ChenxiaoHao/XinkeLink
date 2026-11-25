package cn.xinkelink.statistics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xinkelink.statistics")
public class ViewStatisticsProperties {
    private boolean enabled;
    private Boolean publisherEnabled,consumerEnabled,projectionEnabled;
    private String bootstrapServers="127.0.0.1:39092",topic="xl_post_views_dev_v1",consumerGroup="xl_post_views_dev_v1",namespace="dev";
    private int sendTimeoutMillis=3000,pollMillis=500,projectionMillis=5000,batchSize=16;
    private long leaseMillis=60000;
    public void validate() {
        if(bootstrapServers==null||bootstrapServers.isBlank()||topic==null||!topic.matches("[a-zA-Z0-9_-]{1,180}")
                ||consumerGroup==null||!consumerGroup.matches("[a-zA-Z0-9_-]{1,180}")||namespace==null||!namespace.matches("[a-zA-Z0-9_-]{1,64}")
                ||sendTimeoutMillis<500||sendTimeoutMillis>30000||pollMillis<100||pollMillis>30000||projectionMillis<100||projectionMillis>30000
                ||batchSize<1||batchSize>32||leaseMillis<sendTimeoutMillis+1000||leaseMillis>300000) throw new IllegalArgumentException("Invalid statistics configuration");
    }
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean x){enabled=x;}
    public boolean isPublisherEnabled(){return publisherEnabled==null?enabled:publisherEnabled;} public void setPublisherEnabled(Boolean x){publisherEnabled=x;}
    public boolean isConsumerEnabled(){return consumerEnabled==null?enabled:consumerEnabled;} public void setConsumerEnabled(Boolean x){consumerEnabled=x;}
    public boolean isProjectionEnabled(){return projectionEnabled==null?enabled:projectionEnabled;} public void setProjectionEnabled(Boolean x){projectionEnabled=x;}
    public String getBootstrapServers(){return bootstrapServers;} public void setBootstrapServers(String x){bootstrapServers=x;}
    public String getTopic(){return topic;} public void setTopic(String x){topic=x;}
    public String getConsumerGroup(){return consumerGroup;} public void setConsumerGroup(String x){consumerGroup=x;}
    public String getNamespace(){return namespace;} public void setNamespace(String x){namespace=x;}
    public int getSendTimeoutMillis(){return sendTimeoutMillis;} public void setSendTimeoutMillis(int x){sendTimeoutMillis=x;}
    public int getPollMillis(){return pollMillis;} public void setPollMillis(int x){pollMillis=x;}
    public int getProjectionMillis(){return projectionMillis;} public void setProjectionMillis(int x){projectionMillis=x;}
    public int getBatchSize(){return batchSize;} public void setBatchSize(int x){batchSize=x;}
    public long getLeaseMillis(){return leaseMillis;} public void setLeaseMillis(long x){leaseMillis=x;}
}
