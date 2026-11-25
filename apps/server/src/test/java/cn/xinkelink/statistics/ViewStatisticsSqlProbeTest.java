package cn.xinkelink.statistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ViewStatisticsSqlProbeTest {
    @BeforeEach void resetProbe(){
        ViewStatisticsSqlProbe.counts.clear();ViewStatisticsSqlProbe.nanos.clear();
        ViewStatisticsSqlProbe.accepted.clear();ViewStatisticsSqlProbe.aggregated.clear();
    }

    @Test void countsPlainAndPreparedStatementExecutionsWithoutExportingSql()throws Exception{
        Connection real=mock(Connection.class);Statement plain=mock(Statement.class);PreparedStatement prepared=mock(PreparedStatement.class);
        when(real.createStatement()).thenReturn(plain);when(real.prepareStatement("SELECT confidential_payload FROM private_fixture WHERE id=?")).thenReturn(prepared);
        Connection observed=new ViewStatisticsSqlProbe().connection(real);
        observed.createStatement().executeQuery("SELECT confidential_plain_value");
        observed.prepareStatement("SELECT confidential_payload FROM private_fixture WHERE id=?").executeQuery();
        assertEquals(2L,ViewStatisticsSqlProbe.counts.get("other.sql").get(),"Both JDBC statement forms are application SQL calls");
        String snapshot=JsonMapper.builder().build().writeValueAsString(ViewStatisticsSqlProbe.snapshot());
        assertFalse(snapshot.contains("confidential"));assertFalse(snapshot.contains("SELECT"));assertFalse(snapshot.contains("private_fixture"));
        assertEquals(java.util.Set.of("other.sql"),ViewStatisticsSqlProbe.counts.keySet());
    }

    @Test void failedExecutionsAreCountedAndTheirOriginalExceptionsPropagate()throws Exception{
        Connection real=mock(Connection.class);Statement plain=mock(Statement.class);PreparedStatement prepared=mock(PreparedStatement.class);
        SQLException plainFailure=new SQLException("test-only plain statement failure"),preparedFailure=new SQLException("test-only prepared statement failure");
        when(real.createStatement()).thenReturn(plain);when(real.prepareStatement("UPDATE private_fixture SET value=?")).thenReturn(prepared);
        when(plain.executeUpdate("UPDATE private_fixture SET value=1")).thenThrow(plainFailure);when(prepared.executeUpdate()).thenThrow(preparedFailure);
        Connection observed=new ViewStatisticsSqlProbe().connection(real);
        assertSame(plainFailure,assertThrows(SQLException.class,()->observed.createStatement().executeUpdate("UPDATE private_fixture SET value=1")));
        assertSame(preparedFailure,assertThrows(SQLException.class,()->observed.prepareStatement("UPDATE private_fixture SET value=?").executeUpdate()));
        assertEquals(2L,ViewStatisticsSqlProbe.counts.get("other.sql").get());
    }

    @Test void preparedEventPairingStillRecordsOnlyAfterActualCommit()throws Exception{
        Connection real=mock(Connection.class);PreparedStatement statement=mock(PreparedStatement.class);String id=UUID.randomUUID().toString();
        when(real.prepareStatement("INSERT INTO xl_view_receipt(event_id,payload) VALUES(?,?)")).thenReturn(statement);
        Connection observed=new ViewStatisticsSqlProbe().connection(real);
        var insert=observed.prepareStatement("INSERT INTO xl_view_receipt(event_id,payload) VALUES(?,?)");
        insert.setString(1,id);insert.setString(2,"not-exported-secret-payload");insert.executeUpdate();
        assertFalse(ViewStatisticsSqlProbe.accepted.containsKey(id));
        observed.commit();verify(real).commit();assertTrue(ViewStatisticsSqlProbe.accepted.get(id)>0);
        assertFalse(JsonMapper.builder().build().writeValueAsString(ViewStatisticsSqlProbe.snapshot()).contains("not-exported-secret-payload"));
    }
}
