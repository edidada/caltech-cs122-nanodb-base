package edu.caltech.nanodb.storage.heapfile;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.caltech.nanodb.queryeval.ColumnStats;
import edu.caltech.nanodb.queryeval.ColumnStatsCollector;
import edu.caltech.nanodb.queryeval.TableStats;
import edu.caltech.nanodb.relations.Schema;
import edu.caltech.nanodb.relations.Tuple;
import edu.caltech.nanodb.storage.DBFile;
import edu.caltech.nanodb.storage.DBPage;
import edu.caltech.nanodb.storage.DataFormatException;
import edu.caltech.nanodb.storage.FilePointer;
import edu.caltech.nanodb.storage.InvalidFilePointerException;
import edu.caltech.nanodb.storage.PageTuple;
import edu.caltech.nanodb.storage.StorageManager;
import edu.caltech.nanodb.storage.TupleFile;
import edu.caltech.nanodb.storage.TupleFileException;
import edu.caltech.nanodb.storage.TupleFileManager;


/**
 * This class implements the TupleFile interface for indexed heap files.
 *
 * <p>HW1 disk format: page 0 holds a version marker, persistent physical-page
 * count, schema and statistics. Page 1 is the free-space root. With page size
 * S and K=S/2, leaf g is physical page 2+g*(K+1); its next K pages are ordinary
 * slotted data pages. An unsigned-short leaf entry stores free bytes plus two
 * when an existing empty slot can be reused; root entries store leaf maxima.
 * Allocation searches a fitting root/leaf entry rather than reading each data
 * page. Inserts, deletes and updates refresh the leaf and root on disk, without
 * using TableStats or a process-local allocation cursor. Scans and file-pointer
 * lookups exclude directory pages. Legacy unversioned files must be recreated.
 * See FreeSpaceMap and reports/hw1.md for the physical layout and I/O tradeoffs.
 * All temporary page pins are released; returned tuples own one data-page pin.
 * </p>
 */
public class HeapTupleFile implements TupleFile {

    /** A logging object for reporting anything interesting that happens. */
    private static Logger logger = LogManager.getLogger(HeapTupleFile.class);


    /**
     * The storage manager to use for reading and writing file pages, pinning
     * and unpinning pages, write-ahead logging, and so forth.
     */
    private StorageManager storageManager;


    /**
     * The manager for heap tuple files provides some higher-level operations
     * such as saving the metadata of a heap tuple file, so it's useful to
     * have a reference to it.
     */
    private HeapTupleFileManager heapFileManager;


    /** The schema of tuples in this tuple file. */
    private Schema schema;


    /** Statistics for this tuple file. */
    private TableStats stats;


    /** The file that stores the tuples. */
    private DBFile dbFile;

    private FreeSpaceMap freeSpaceMap;


    HeapTupleFile(StorageManager storageManager,
                  HeapTupleFileManager heapFileManager, DBFile dbFile,
                  Schema schema, TableStats stats) {
        if (storageManager == null)
            throw new IllegalArgumentException("storageManager cannot be null");

        if (heapFileManager == null)
            throw new IllegalArgumentException("heapFileManager cannot be null");

        if (dbFile == null)
            throw new IllegalArgumentException("dbFile cannot be null");

        if (schema == null)
            throw new IllegalArgumentException("schema cannot be null");

        if (stats == null)
            throw new IllegalArgumentException("stats cannot be null");

        this.storageManager = storageManager;
        this.heapFileManager = heapFileManager;
        this.dbFile = dbFile;
        this.freeSpaceMap = new FreeSpaceMap(storageManager, dbFile);
        this.schema = schema;
        this.stats = stats;
    }


    @Override
    public TupleFileManager getManager() {
        return heapFileManager;
    }


    @Override
    public Schema getSchema() {
        return schema;
    }

    @Override
    public TableStats getStats() {
        return stats;
    }


    public DBFile getDBFile() {
        return dbFile;
    }


    /**
     * Returns the first tuple in this table file, or <tt>null</tt> if
     * there are no tuples in the file.
     */
    @Override
    public Tuple getFirstTuple() {
        return scan(3, 0);
    }

    private HeapFilePageTuple scan(int pageNo, int firstSlot) {
        int count = freeSpaceMap.pageCount();
        for (int page = pageNo; page < count; page++) {
            if (!freeSpaceMap.isDataPage(page))
                continue;
            DBPage data = storageManager.loadDBPage(dbFile, page);
            if (data == null)
                throw new DataFormatException("Missing heap data page " + page);
            try {
                int start = page == pageNo ? firstSlot : 0;
                for (int slot = start; slot < DataPage.getNumSlots(data); slot++) {
                    int offset = DataPage.getSlotValue(data, slot);
                    if (offset != DataPage.EMPTY_SLOT)
                        return new HeapFilePageTuple(schema, data, slot, offset);
                }
            }
            finally { data.unpin(); }
        }
        return null;
    }


    /**
     * Returns the tuple corresponding to the specified file pointer.  This
     * method is used by many other operations in the database, such as
     * indexes.
     *
     * @throws InvalidFilePointerException if the specified file-pointer
     *         doesn't actually point to a real tuple.
     */
    @Override
    public Tuple getTuple(FilePointer fptr) throws InvalidFilePointerException {

        if (!freeSpaceMap.isDataPage(fptr.getPageNo()) ||
            fptr.getPageNo() >= freeSpaceMap.pageCount())
            throw new InvalidFilePointerException("Not an allocated heap data page");
        DBPage dbPage = storageManager.loadDBPage(dbFile, fptr.getPageNo());
        if (dbPage == null) {
            throw new InvalidFilePointerException(String.format(
                "Specified page %d doesn't exist in file %s",
                fptr.getPageNo(), dbFile.getDataFile().getName()));
        }

        try {
            // The file-pointer points to the slot for the tuple, not the tuple itself.
            // So, we need to look up that slot's value to get to the tuple data.

            int slot;
            try {
                slot = DataPage.getSlotIndexFromOffset(dbPage, fptr.getOffset());
            }
            catch (IllegalArgumentException iae) {
                throw new InvalidFilePointerException(iae);
            }

            // Pull the tuple's offset from the specified slot, and make sure
            // there is actually a tuple there!

            int offset = DataPage.getSlotValue(dbPage, slot);
            if (offset == DataPage.EMPTY_SLOT) {
                throw new InvalidFilePointerException("Slot " + slot +
                    " on page " + fptr.getPageNo() + " is empty.");
            }

            return new HeapFilePageTuple(schema, dbPage, slot, offset);
        }
        finally {
            dbPage.unpin();
        }
    }


    /**
     * Returns the tuple that follows the specified tuple, or {@code null} if
     * there are no more tuples in the file.  This method must operate
     * correctly regardless of whether the input tuple is pinned or unpinned.
     *
     * @param tup the "previous tuple" that specifies where to start looking
     *        for the next tuple
     */
    @Override
    public Tuple getNextTuple(Tuple tup) {

        /* Procedure:
         *   1)  Get slot index of current tuple.
         *   2)  If there are more slots in the current page, find the next
         *       non-empty slot.
         *   3)  If we get to the end of this page, go to the next page
         *       and try again.
         *   4)  If we get to the end of the file, we return null.
         */

        if (!(tup instanceof HeapFilePageTuple)) {
            throw new IllegalArgumentException(
                "Tuple must be of type HeapFilePageTuple; got " + tup.getClass());
        }
        HeapFilePageTuple ptup = (HeapFilePageTuple) tup;

        // Only immutable page identity and slot are read from an unpinned tuple.
        return scan(ptup.getDBPage().getPageNo(), ptup.getSlot() + 1);
    }


    /**
     * Adds the specified tuple into the table file.  A new
     * <tt>HeapFilePageTuple</tt> object corresponding to the tuple is returned.
     *
     * @review (donnie) This could be made a little more space-efficient.
     *         Right now when computing the required space, we assume that we
     *         will <em>always</em> need a new slot entry, whereas the page may
     *         contain empty slots.  (Note that we don't always create a new
     *         slot when adding a tuple; we will reuse an empty slot.  This
     *         inefficiency is simply in estimating the size required for the
     *         new tuple.)
     */
    @Override
    public Tuple addTuple(Tuple tup) {

        /*
         * Find out how large the new tuple will be, so we can find a page to
         * store it.
         *
         * Find a page with space for the new tuple.
         *
         * Generate the data necessary for storing the tuple into the file.
         */

        int tupSize = PageTuple.getTupleStorageSize(schema, tup);
        logger.debug("Adding new tuple of size " + tupSize + " bytes.");

        // Sanity check:  Make sure that the tuple would actually fit in a page
        // in the first place!
        // The "+ 2" is for the case where we need a new slot entry as well.
        if (tupSize + 4 > dbFile.getPageSize()) {
            throw new TupleFileException("Tuple size " + tupSize +
                " is larger than page size " + dbFile.getPageSize() + ".");
        }

        int pageNo = freeSpaceMap.find(tupSize + 2);
        DBPage dbPage = storageManager.loadDBPage(dbFile, pageNo);

        try {
            int slot = DataPage.allocNewTuple(dbPage, tupSize);
            int tupOffset = DataPage.getSlotValue(dbPage, slot);

            logger.debug(String.format(
                "New tuple will reside on page %d, slot %d.", pageNo, slot));

            HeapFilePageTuple pageTup =
                HeapFilePageTuple.storeNewTuple(schema, dbPage, slot, tupOffset, tup);

            DataPage.sanityCheck(dbPage);
            storageManager.logDBPageWrite(dbPage);
            freeSpaceMap.update(dbPage);

            return pageTup;
        }
        finally {
            dbPage.unpin();
        }
    }


    // Inherit interface-method documentation.
    /**
     * @review (donnie) This method will fail if a tuple is modified in a way
     *         that requires more space than is currently available in the data
     *         page.  One solution would be to move the tuple to a different
     *         page and then perform the update, but that would cause all kinds
     *         of additional issues.  So, if the page runs out of data, oh well.
     */
    @Override
    public void updateTuple(Tuple tup, Map<String, Object> newValues) {

        if (!(tup instanceof HeapFilePageTuple)) {
            throw new IllegalArgumentException(
                "Tuple must be of type HeapFilePageTuple; got " + tup.getClass());
        }
        HeapFilePageTuple ptup = (HeapFilePageTuple) tup;

        try {
            for (Map.Entry<String, Object> entry : newValues.entrySet()) {
                String colName = entry.getKey();
                Object value = entry.getValue();

                int colIndex = schema.getColumnIndex(colName);
                ptup.setColumnValue(colIndex, value);
            }

            DataPage.sanityCheck(ptup.getDBPage());
        }
        finally {
            storageManager.logDBPageWrite(ptup.getDBPage());
            freeSpaceMap.update(ptup.getDBPage());
        }
    }


    // Inherit interface-method documentation.
    @Override
    public void deleteTuple(Tuple tup) {

        if (!(tup instanceof HeapFilePageTuple)) {
            throw new IllegalArgumentException(
                "Tuple must be of type HeapFilePageTuple; got " + tup.getClass());
        }
        HeapFilePageTuple ptup = (HeapFilePageTuple) tup;

        DBPage dbPage = ptup.getDBPage();
        DataPage.deleteTuple(dbPage, ptup.getSlot());
        DataPage.sanityCheck(dbPage);
        storageManager.logDBPageWrite(dbPage);
        freeSpaceMap.update(dbPage);

        // Note that we don't invalidate the page-tuple when it is deleted,
        // so that the tuple can still be unpinned, etc.
    }


    @Override
    public void analyze() {
        ColumnStatsCollector[] collectors = new ColumnStatsCollector[schema.numColumns()];
        for (int i = 0; i < collectors.length; i++)
            collectors[i] = new ColumnStatsCollector(schema.getColumnInfo(i).getType().getBaseType());
        int pages = 0, tuples = 0;
        long bytes = 0;
        int count = freeSpaceMap.pageCount();
        for (int page = 3; page < count; page++) {
            if (!freeSpaceMap.isDataPage(page)) continue;
            DBPage data = storageManager.loadDBPage(dbFile, page);
            try {
                pages++;
                bytes += DataPage.getTupleDataEnd(data) - DataPage.getTupleDataStart(data);
                for (int slot = 0; slot < DataPage.getNumSlots(data); slot++) {
                    int offset = DataPage.getSlotValue(data, slot);
                    if (offset == DataPage.EMPTY_SLOT) continue;
                    HeapFilePageTuple tuple = new HeapFilePageTuple(schema, data, slot, offset);
                    try {
                        tuples++;
                        for (int i = 0; i < collectors.length; i++)
                            collectors[i].addValue(tuple.getColumnValue(i));
                    }
                    finally { tuple.unpin(); }
                }
            }
            finally { data.unpin(); }
        }
        ArrayList<ColumnStats> columns = new ArrayList<>();
        for (ColumnStatsCollector collector : collectors) columns.add(collector.getColumnStats());
        stats = new TableStats(pages, tuples, tuples == 0 ? 0 : (float) bytes / tuples, columns);
        heapFileManager.saveMetadata(this);
    }


    @Override
    public List<String> verify() {
        // TODO!
        // Right now we will just report that everything is fine.
        return new ArrayList<>();
    }


    @Override
    public void optimize() {
        // TODO!
        throw new UnsupportedOperationException("Not yet implemented!");
    }
}
