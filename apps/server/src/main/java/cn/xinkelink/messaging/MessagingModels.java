package cn.xinkelink.messaging;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import jakarta.validation.constraints.*;
import java.math.BigInteger;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class MessagingModels {
    private MessagingModels() {}
    static final BigInteger MAX_SEQUENCE=new BigInteger("18446744073709551615");
    public record OpenConversation(@NotBlank String peerUserId) {}
    public record SendMessage(@NotBlank @Size(max=1000) String text,
            @NotBlank @Pattern(regexp="[\\x21-\\x7E]{1,64}") String clientMessageId) {}
    public record Read(@NotNull @JsonDeserialize(using=SequenceDeserializer.class) String readUpToSequence) {}
    public record Clear(@NotNull @JsonDeserialize(using=SequenceDeserializer.class) String clearThroughSequence,@NotNull @AssertTrue Boolean confirmed) {}
    public record Settings(Boolean pinned,Boolean muted,@NotNull @PositiveOrZero Long expectedVersion) {}
    static BigInteger sequence(String value) {
        if(value==null||!value.matches("(0|[1-9][0-9]{0,19})"))throw invalid();
        BigInteger sequence=new BigInteger(value);
        if(sequence.compareTo(MAX_SEQUENCE)>0)throw invalid();
        return sequence;
    }
    private static BusinessException invalid(){return new BusinessException(ErrorCode.VALIDATION_FAILED);}
    public static final class SequenceDeserializer extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser parser,DeserializationContext context) {
            if(parser.currentToken()!=JsonToken.VALUE_STRING)return context.reportInputMismatch(String.class,"消息水位必须为十进制字符串");
            return parser.getString();
        }
    }
}
