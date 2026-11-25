package cn.xinkelink.notification;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.ByteBuf;
import org.apache.rocketmq.remoting.netty.NettyEncoder;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.header.namesrv.GetRouteInfoRequestHeader;
import org.apache.rocketmq.remoting.protocol.RequestCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RocketMqProtocolCompatibilityTest {
    @Test void nativeRequestEncodesWithBootManagedNetty(){var header=new GetRouteInfoRequestHeader();header.setTopic("xl_interactions_protocol_test");var command=RemotingCommand.createRequestCommand(RequestCode.GET_ROUTEINFO_BY_TOPIC,header);
        var channel=new EmbeddedChannel(new NettyEncoder());try{assertTrue(channel.writeOutbound(command));ByteBuf result=channel.readOutbound();assertTrue(result.readableBytes()>8);result.release();}finally{channel.finishAndReleaseAll();}}
}
