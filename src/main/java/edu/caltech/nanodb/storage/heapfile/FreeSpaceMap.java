package edu.caltech.nanodb.storage.heapfile;

import edu.caltech.nanodb.storage.DBFile;
import edu.caltech.nanodb.storage.DBPage;
import edu.caltech.nanodb.storage.DataFormatException;
import edu.caltech.nanodb.storage.StorageManager;
import edu.caltech.nanodb.storage.TupleFileException;

/** Persistent two-level map. Entries are unsigned-short insertion capacities;
 * zero denotes an unallocated or completely full data page. No TableStats or
 * process-local allocation cursor is needed to reopen a file. */
final class FreeSpaceMap {
    static final int MAGIC = 0x48573101;
    static final int MAGIC_OFFSET = 2;
    static final int COUNT_OFFSET = 6;
    private final StorageManager storage;
    private final DBFile file;
    private final int fanout;

    FreeSpaceMap(StorageManager storage, DBFile file) {
        this.storage = storage;
        this.file = file;
        fanout = file.getPageSize() / 2;
    }

    boolean isDataPage(int page) {
        return page >= 3 && (page - 2) % (fanout + 1) != 0;
    }

    void initialize() {
        DBPage root = storage.loadDBPage(file, 1, true);
        try {
            root.setDataRange(0, file.getPageSize(), (byte) 0);
            storage.logDBPageWrite(root);
        }
        finally { root.unpin(); }
        DBPage header = storage.loadDBPage(file, 0);
        try {
            header.writeInt(MAGIC_OFFSET, MAGIC);
            header.writeInt(COUNT_OFFSET, 2);
            storage.logDBPageWrite(header);
        }
        finally { header.unpin(); }
    }

    int pageCount() {
        DBPage header = storage.loadDBPage(file, 0);
        try {
            if (header.readInt(MAGIC_OFFSET) != MAGIC)
                throw new DataFormatException("Not an HW1 indexed heap file; recreate legacy tables");
            int count = header.readInt(COUNT_OFFSET);
            if (count < 2 || count > 65536)
                throw new DataFormatException("Invalid indexed heap page count: " + count);
            return count;
        }
        finally { header.unpin(); }
    }

    /** Find the first fitting existing page, otherwise append one data page. */
    int find(int required) {
        int count = pageCount();
        int group = -1;
        DBPage root = storage.loadDBPage(file, 1);
        try {
            for (int g = 0; 2 + g * (fanout + 1) < count; g++) {
                if (root.readUnsignedShort(2 * g) >= required) {
                    group = g;
                    break;
                }
            }
        }
        finally { root.unpin(); }
        if (group >= 0) {
            int leafNo = 2 + group * (fanout + 1);
            DBPage leaf = storage.loadDBPage(file, leafNo);
            try {
                for (int i = 0; i < fanout && leafNo + 1 + i < count; i++) {
                    if (leaf.readUnsignedShort(2 * i) >= required)
                        return leafNo + 1 + i;
                }
                throw new DataFormatException("Free-space root/leaf disagree");
            }
            finally { leaf.unpin(); }
        }
        int page = count;
        if (!isDataPage(page)) {
            if (page + 1 >= 65536)
                throw new TupleFileException("Heap file reached 65536-page limit");
            DBPage leaf = storage.loadDBPage(file, page++, true);
            try {
                leaf.setDataRange(0, file.getPageSize(), (byte) 0);
                storage.logDBPageWrite(leaf);
            }
            finally { leaf.unpin(); }
        }
        if (page >= 65536)
            throw new TupleFileException("Heap file reached 65536-page limit");
        DBPage data = storage.loadDBPage(file, page, true);
        try {
            DataPage.initNewPage(data);
            storage.logDBPageWrite(data);
        }
        finally { data.unpin(); }
        DBPage header = storage.loadDBPage(file, 0);
        try {
            header.writeInt(COUNT_OFFSET, page + 1);
            storage.logDBPageWrite(header);
        }
        finally { header.unpin(); }
        return page;
    }

    /** Called after every data-page mutation, including deletes and shrinks. */
    void update(DBPage data) {
        int page = data.getPageNo();
        if (!isDataPage(page))
            throw new IllegalArgumentException("Not a heap data page: " + page);
        int capacity = DataPage.getFreeSpaceInPage(data);
        for (int i = 0; i < DataPage.getNumSlots(data); i++) {
            if (DataPage.getSlotValue(data, i) == DataPage.EMPTY_SLOT) {
                capacity += 2;
                break;
            }
        }
        int group = (page - 2) / (fanout + 1);
        int leafNo = 2 + group * (fanout + 1);
        int entry = page - leafNo - 1;
        int maximum = 0;
        int entries = Math.min(fanout, pageCount() - leafNo - 1);
        DBPage leaf = storage.loadDBPage(file, leafNo);
        try {
            leaf.writeShort(entry * 2, capacity);
            for (int i = 0; i < entries; i++)
                maximum = Math.max(maximum, leaf.readUnsignedShort(i * 2));
            storage.logDBPageWrite(leaf);
        }
        finally { leaf.unpin(); }
        DBPage root = storage.loadDBPage(file, 1);
        try {
            if (root.readUnsignedShort(group * 2) != maximum) {
                root.writeShort(group * 2, maximum);
                storage.logDBPageWrite(root);
            }
        }
        finally { root.unpin(); }
    }
}
