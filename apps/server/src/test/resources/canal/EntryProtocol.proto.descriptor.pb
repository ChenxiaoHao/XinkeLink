
EntryProtocol.proto com.alibaba.otter.canal.protocol"ž
Entry8
header (2(.com.alibaba.otter.canal.protocol.HeaderG
	entryType (2+.com.alibaba.otter.canal.protocol.EntryType:ROWDATA

storeValue ("‘
Header
version (:1
logfileName (	
logfileOffset (
serverId (
serverenCode (	
executeTime (A

sourceType (2&.com.alibaba.otter.canal.protocol.Type:MYSQL

schemaName (	
	tableName	 (	
eventLength
 (F
	eventType (2+.com.alibaba.otter.canal.protocol.EventType:UPDATE5
props (2&.com.alibaba.otter.canal.protocol.Pair
gtid (	"Ö
Column
index (
sqlType (
name (	
isKey (
updated (
isNull (:false5
props (2&.com.alibaba.otter.canal.protocol.Pair
value (	
length	 (
	mysqlType
 (	"Á
RowData?
beforeColumns (2(.com.alibaba.otter.canal.protocol.Column>
afterColumns (2(.com.alibaba.otter.canal.protocol.Column5
props (2&.com.alibaba.otter.canal.protocol.Pair"’
	RowChange
tableId (F
	eventType (2+.com.alibaba.otter.canal.protocol.EventType:UPDATE
isDdl
 (:false
sql (	;
rowDatas (2).com.alibaba.otter.canal.protocol.RowData5
props (2&.com.alibaba.otter.canal.protocol.Pair
ddlSchemaName (	"‡
TransactionBegin
executeTime (
transactionId (	5
props (2&.com.alibaba.otter.canal.protocol.Pair
threadId ("s
TransactionEnd
executeTime (
transactionId (	5
props (2&.com.alibaba.otter.canal.protocol.Pair""
Pair
key (	
value (	*^
	EntryType
TRANSACTIONBEGIN
ROWDATA
TRANSACTIONEND
	HEARTBEAT
GTIDLOG*Æ
	EventType

INSERT

UPDATE

DELETE

CREATE	
ALTER	
ERASE	
QUERY
TRUNCATE

RENAME	

CINDEX


DINDEX
GTID
XACOMMIT

XAROLLBACK

MHEARTBEAT*(
Type

ORACLE	
MYSQL	
PGSQLB0
 com.alibaba.otter.canal.protocolB
CanalEntryH