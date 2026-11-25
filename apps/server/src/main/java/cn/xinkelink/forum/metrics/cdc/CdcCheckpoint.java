package cn.xinkelink.forum.metrics.cdc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Durable client audit, not a replacement for Canal's server-side ACK cursor. */
final class CdcCheckpoint implements AutoCloseable {
    private final Path path;
    private final String destination, schema;
    private FileChannel channel;
    private FileLock lock;
    private FileChannel identityChannel;
    private final Path identityPath;
    CdcCheckpoint(Path path, String destination, String schema, int port) {
        this.path = path; this.destination = destination; this.schema = schema;
        try {
            String identity=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("loopback:"+port+":"+destination+":1001").getBytes(StandardCharsets.UTF_8)));
            identityPath=Path.of(System.getProperty("user.home"),".xinkelink","cdc-owners",identity+".lock");
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException("SHA-256 is unavailable");}
    }
    synchronized void acquire() throws IOException {
        if (lock != null && lock.isValid()) return;
        // Canonical endpoint/destination ownership is independent of the chosen checkpoint path.
        // The supported topology is one local host/user; this is not distributed failover fencing.
        Files.createDirectories(identityPath.getParent());
        identityChannel=FileChannel.open(identityPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            if(identityChannel.tryLock()==null) throw new IOException("CDC identity already active");
        } catch(IOException | OverlappingFileLockException failure) {
            identityChannel.close();identityChannel=null;throw new IOException("CDC identity already active");
        }
        try {
        Files.createDirectories(path.getParent());
        channel = FileChannel.open(path.resolveSibling(path.getFileName()+".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException failure) { channel.close(); channel=null; throw new IOException("CDC owner already active"); }
        if (lock == null) { channel.close(); channel=null; throw new IOException("CDC owner already active"); }
        try { if (Files.exists(path)) {
            var saved = new Properties();
            try (var input=Files.newBufferedReader(path,StandardCharsets.UTF_8)) { saved.load(input); }
            if (!destination.equals(saved.getProperty("destination")) || !schema.equals(saved.getProperty("schema"))
                    || !"1001".equals(saved.getProperty("client_id"))) {
                throw new IOException("CDC checkpoint identity mismatch");
            }
        } } catch(IOException | RuntimeException failure) { close(); throw new IOException("CDC checkpoint cannot be verified"); }
        } catch(IOException | RuntimeException failure) {close();throw new IOException("CDC owner cannot be acquired");}
    }
    synchronized void save(String phase, long batch, String position, long affected, long events, long eventTime) throws IOException {
        if (lock == null || !lock.isValid()) throw new IOException("CDC owner not held");
        String value="destination="+destination+"\nschema="+schema+"\nclient_id=1001\nphase="+phase
                +"\nbatch_id="+batch+"\nposition="+position+"\naffected_posts="+affected+"\nrelevant_events="+events
                +"\nevent_time="+eventTime+"\nwritten_at="+System.currentTimeMillis()+"\n";
        atomic(path,value);
    }
    synchronized void status(String state, String phase) throws IOException {
        if(lock==null || !lock.isValid()) throw new IOException("CDC owner not held");
        atomic(path.resolveSibling(path.getFileName()+".status"),"state="+state+"\nphase="+phase+"\nwritten_at="+System.currentTimeMillis()+"\n");
    }
    private static void atomic(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),".cdc-", ".state");
        try (var output=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
            var data=ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while(data.hasRemaining()) output.write(data);
            output.force(true);
        }
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public synchronized void close() throws IOException {
        try {if(channel!=null) channel.close();}
        finally {
            channel=null;lock=null;
            if(identityChannel!=null) identityChannel.close();
            identityChannel=null;
        }
    }
}
