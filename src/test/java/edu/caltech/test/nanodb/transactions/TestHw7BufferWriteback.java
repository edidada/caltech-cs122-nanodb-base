package edu.caltech.test.nanodb.transactions;

import java.nio.file.Files;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.mockito.InOrder;
import static org.mockito.Mockito.*;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.server.properties.PropertyRegistry;

/** Verify the observable WAL-before-data ordering when eviction fills the cache. */
@Test(groups={"hw7"})
public class TestHw7BufferWriteback {
    public void testEvictionKeepsWALVisibleAndAllowsObserverScratch() throws Exception {
        FileManager files=spy(new FileManagerImpl(Files.createTempDirectory("hw7-writeback-").toFile()));
        BufferManager buffer=new BufferManager(files,new PropertyRegistry());
        buffer.setMaxCacheSize(65536);
        DBFile heap=files.createDBFile("heap.dat",DBFileType.HEAP_TUPLE_FILE,8192);
        DBFile wal=files.createDBFile("wal.dat",DBFileType.WRITE_AHEAD_LOG_FILE,8192);
        buffer.addFile(heap);buffer.addFile(wal);
        // Put data before WAL in the eviction order. Unordered batch writes
        // would persist data first; forcing must override that order.
        DBPage data=buffer.getPage(heap,1,true);data.writeByte(64,7);data.unpin();
        DBPage log=buffer.getPage(wal,1,true);log.writeByte(64,9);log.unpin();
        byte[] held=buffer.allocBuffer(32768); // Two dirty pages use the other 32 KiB.
        buffer.addObserver(pages -> {
            if(pages.stream().noneMatch(p -> p.getDBFile().equals(heap)))return;
            DBPage visible=buffer.getPage(wal,1,false);
            Assert.assertSame(visible,log,"Eviction hid the dirty WAL buffer from its force operation");
            visible.unpin();
            byte[] temporary=buffer.allocBuffer(8192);
            buffer.releaseBuffer(temporary);
            buffer.writeDBFile(wal,true);
        });
        clearInvocations(files);
        byte[] next=buffer.allocBuffer(8192); // Actual capacity pressure invokes eviction.
        InOrder order=inOrder(files);
        order.verify(files).savePage(eq(wal),eq(1),any(byte[].class));
        order.verify(files).syncDBFile(wal);
        order.verify(files).savePage(eq(heap),eq(1),any(byte[].class));
        buffer.releaseBuffer(next);buffer.releaseBuffer(held);
        buffer.removeAll();files.closeDBFile(heap);files.closeDBFile(wal);
    }
}
