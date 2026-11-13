package cn.xinkelink.seed;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalSeedDataTest {
    @Test void planContainsTheRequestedBusinessVolumeWithoutLoginCredentials(){
        assertEquals(Map.of("users",100,"posts",500,"comments",2000,"relations",2400,"conversations",150,"messages",1000,"reports",48,"notifications",472,"announcements",15),LocalSeedData.plan());
    }
    @Test void productionAndRemoteDatabasesAreRefusedBeforeConnecting(){
        assertThrows(IllegalArgumentException.class,()->LocalSeedData.checkDatabase("jdbc:mysql://prod.example:3306/xinkelink_dev"));
        assertThrows(IllegalArgumentException.class,()->LocalSeedData.checkDatabase("jdbc:mysql://127.0.0.1:3306/xinkelink_prod"));
        assertDoesNotThrow(()->LocalSeedData.checkDatabase("jdbc:mysql://127.0.0.1:13306/xinkelink_dev?useSSL=false"));
    }
}
