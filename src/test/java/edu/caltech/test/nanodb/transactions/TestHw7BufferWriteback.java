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
    public void testEvictionKeepsWALVisibleAndAllowsObserverScratch() throws Exception {verifyWriteback(false,false);}
    public void testFlushAllKeepsWALVisible() throws Exception {verifyWriteback(true,false);}
    public void testFailedObserverKeepsDirtyPagesForRetry() throws Exception {verifyWriteback(false,true);}
    private void verifyWriteback(boolean flushAll,boolean failOnce) throws Exception {
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
        boolean[] reject={failOnce};
        buffer.addObserver(pages -> {
            if(pages.stream().noneMatch(p -> p.getDBFile().equals(heap)))return;
            if(reject[0]) {reject[0]=false;throw new IllegalStateException("injected WAL force failure");}
            DBPage visible=buffer.getPage(wal,1,false);
            Assert.assertSame(visible,log,"Eviction hid the dirty WAL buffer from its force operation");
            visible.unpin();
            byte[] temporary=buffer.allocBuffer(8192);
            buffer.releaseBuffer(temporary);
            buffer.writeDBFile(wal,true);
        });
        clearInvocations(files);
        if(failOnce) {
            try {buffer.allocBuffer(8192);Assert.fail("Expected injected force failure");}
            catch(IllegalStateException expected) {Assert.assertEquals(expected.getMessage(),"injected WAL force failure");}
            verify(files,never()).savePage(eq(heap),eq(1),any(byte[].class));
            DBPage retained=buffer.getPage(heap,1,false);
            Assert.assertSame(retained,data);Assert.assertTrue(retained.isDirty());retained.unpin();
        }
        byte[] next=null;
        if(flushAll)buffer.flushAll();
        else next=buffer.allocBuffer(8192); // Actual capacity pressure invokes eviction.
        InOrder order=inOrder(files);
        order.verify(files).savePage(eq(wal),eq(1),any(byte[].class));
        order.verify(files).syncDBFile(wal);
        order.verify(files).savePage(eq(heap),eq(1),any(byte[].class));
        if(next!=null)buffer.releaseBuffer(next);
        buffer.releaseBuffer(held);
        buffer.removeAll();files.closeDBFile(heap);files.closeDBFile(wal);
    }
}
