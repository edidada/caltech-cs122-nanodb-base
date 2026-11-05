package edu.caltech.test.nanodb.transactions;

import java.math.*;
import java.nio.file.Files;
import org.testng.Assert;
import org.testng.annotations.Test;
import edu.caltech.nanodb.storage.*;
import edu.caltech.nanodb.server.properties.PropertyRegistry;

/** Signed decimal persistence, including byte and full-width boundaries. */
@Test(groups={"challenge1"})
public class TestChallenge1Numeric {
    public void testSignedNumericSurvivesPhysicalReopen() throws Exception {
        BigDecimal[] values={new BigDecimal("-1"),new BigDecimal("-0.01"),new BigDecimal("-128"),
            new BigDecimal("-129"),new BigDecimal("-32768"),new BigDecimal("-32769"),
            new BigDecimal("-711.56"),BigDecimal.ZERO,new BigDecimal("127.99"),
            new BigDecimal("128"),new BigDecimal("255"),new BigDecimal("256"),
            new BigDecimal(BigInteger.ONE.shiftLeft(119).negate(),-4),
            new BigDecimal(BigInteger.ONE.shiftLeft(119).subtract(BigInteger.ONE),2)};
        FileManager files=new FileManagerImpl(Files.createTempDirectory("challenge1-numeric-").toFile());
        BufferManager pool=new BufferManager(files,new PropertyRegistry());
        DBFile file=files.createDBFile("signed.dat",DBFileType.TEST_FILE,8192);pool.addFile(file);
        DBPage page=pool.getPage(file,0,true);
        try {
            for(int i=0;i<values.length;i++)page.writeNumeric(32+16*i,values[i]);
            for(int i=0;i<values.length;i++)Assert.assertEquals(page.readNumeric(32+16*i),values[i]);
            try {page.writeNumeric(4096,new BigDecimal(BigInteger.ONE.shiftLeft(119)));Assert.fail("Overflow accepted");}
            catch(IllegalArgumentException expected){Assert.assertTrue(expected.getMessage().contains("15 bytes"));}
        }finally{page.unpin();}
        pool.removeAll();files.closeDBFile(file);
        file=files.openDBFile("signed.dat");pool.addFile(file);page=pool.getPage(file,0,false);
        try{for(int i=0;i<values.length;i++)Assert.assertEquals(page.readNumeric(32+16*i),values[i]);}
        finally{page.unpin();pool.removeAll();files.closeDBFile(file);}
    }
}
