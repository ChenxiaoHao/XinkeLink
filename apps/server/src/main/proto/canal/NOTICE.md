These protocol definitions originate from Alibaba Canal 1.1.8
(Apache License 2.0, tag `canal-1.1.8`, commit `0741ccd`).

`CanalProtocol.proto` is an unchanged copy of the upstream tag's proto3 source.
`EntryProtocol.proto` is reconstructed from the `CanalEntry.getDescriptor()`
embedded in the official Maven artifact `com.alibaba.otter:canal.protocol:1.1.8`.
That released class embeds a proto2 schema, while the tag's EntryProtocol.proto
is proto3. Using the tag's Entry source would change field presence, enum sets,
and defaults (including Header.version=1, sourceType=MYSQL and eventType=UPDATE).
The reconstructed source preserves the released descriptor's fields, labels,
defaults, enum numbers and options. It adds no protocol fields or behaviors.

Sources:
- https://github.com/alibaba/canal/blob/canal-1.1.8/protocol/src/main/java/com/alibaba/otter/canal/protocol/EntryProtocol.proto
- https://github.com/alibaba/canal/blob/canal-1.1.8/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalProtocol.proto
- https://github.com/alibaba/canal/blob/canal-1.1.8/LICENSE

The original released descriptors are retained as binary test resources under
`src/test/resources/canal/`. Compatibility tests compare their complete schemas
with regenerated descriptors, ignoring only the source filename, implicit proto2
syntax spelling and protoc-generated JSON-name metadata.

Artifact SHA-256:
`254cb95712dd2bb2fdc5fa92687f58e1cad6c643057249037ab67e1ca89394e8`.
Entry descriptor SHA-256:
`ce554c725a252976e98e511a96349cd0aa4b1614eb82cac8e55fec0dc08d7ef3`.
Packet descriptor SHA-256:
`1320fc188232460e43e8534df28da5cd024ea148d59c4aabe86d99f217421c38`.

The build regenerates CanalEntry and CanalPacket using protoc matching the
already resolved Protobuf 4.35.1 runtime. Application classes take precedence
over the old generated classes bundled in canal.protocol. Generated Java stays
in target; it is not checked in. Compatibility tests enable the runtime's unsafe
old-gencode error check and verify the application class resource location.
This addresses GHSA-h4h5-3hr4-j3g2 without disabling validation or downgrading
the runtime. The wire schema and Canal client/server version remain 1.1.8.
