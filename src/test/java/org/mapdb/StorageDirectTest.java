package org.mapdb;


import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;

public class StorageDirectTest extends StorageTestCase {


    /** recid used for testing, it is actually free slot with size 1*/
    static final long TEST_LS_RECID = StorageDirect.RECID_FREE_PHYS_RECORDS_START+1 ;

    protected void commit(){
        engine.commit();
    }

    @Test public void testSetGet(){
        long recid  = engine.recordPut((long) 10000, Serializer.LONG_SERIALIZER);
        Long  s2 = engine.recordGet(recid, Serializer.LONG_SERIALIZER);
        assertEquals(s2, Long.valueOf(10000));
    }


    @Test public void test_index_record_delete(){
        long recid = engine.recordPut(1000L, Serializer.LONG_SERIALIZER);
        commit();
        assertEquals(1, countIndexRecords());
        engine.recordDelete(recid);
        commit();
        assertEquals(0, countIndexRecords());
    }

    @Test public void test_index_record_delete_and_reusef(){
        long recid = engine.recordPut(1000L, Serializer.LONG_SERIALIZER);
        commit();
        assertEquals(1, countIndexRecords());
        assertEquals(StorageDirect.INDEX_OFFSET_START, recid);
        engine.recordDelete(recid);
        commit();
        assertEquals(0, countIndexRecords());
        long recid2 = engine.recordPut(1000L, Serializer.LONG_SERIALIZER);
        commit();
        //test that previously deleted index slot was reused
        assertEquals(recid, recid2);
        assertEquals(1, countIndexRecords());
        assertTrue(getIndexRecord(recid) != 0);
    }

    @Test public void test_index_record_delete_and_reuse_large(){
        final long MAX = 10;

        List<Long> recids= new ArrayList<Long>();
        for(int i = 0;i<MAX;i++){
            recids.add(engine.recordPut(0L, Serializer.LONG_SERIALIZER));
        }

        for(long recid:recids){
            engine.recordDelete(recid);
        }

        //now allocate again second recid list
        List<Long> recids2= new ArrayList<Long>();
        for(int i = 0;i<MAX;i++){
            recids2.add(engine.recordPut(0L, Serializer.LONG_SERIALIZER));
        }

        //second list should be reverse of first, as Linked Offset List is LIFO
        Collections.reverse(recids);
        assertEquals(recids, recids2);

    }



    @Test public void test_phys_record_reused(){
        final long recid = engine.recordPut(1L, Serializer.LONG_SERIALIZER);
        final long physRecid = getIndexRecord(recid);
        engine.recordDelete(recid);
        final long recid2 = engine.recordPut(1L, Serializer.LONG_SERIALIZER);

        assertEquals(recid, recid2);
        assertEquals(physRecid, getIndexRecord(recid));

    }



    @Test public void test_index_stores_record_size() throws IOException {

        final long recid = engine.recordPut(1, Serializer.INTEGER_SERIALIZER);
        commit();
        assertEquals(4, readUnsignedShort(engine.index.buffers[0], recid * 8));
        assertEquals(Integer.valueOf(1), engine.recordGet(recid, Serializer.INTEGER_SERIALIZER));

        engine.recordUpdate(recid, 1L, Serializer.LONG_SERIALIZER);
        commit();
        assertEquals(8, readUnsignedShort(engine.index.buffers[0], recid * 8));
        assertEquals(Long.valueOf(1), engine.recordGet(recid, Serializer.LONG_SERIALIZER));

    }

    @Test public void test_long_stack_puts_record_size_into_index() throws IOException {
        engine.lock.writeLock().lock();
        engine.longStackPut(TEST_LS_RECID, 1);
        commit();
        assertEquals(StorageDirect.LONG_STACK_PAGE_SIZE,
                readUnsignedShort(engine.index.buffers[0], TEST_LS_RECID * 8));

    }

    @Test public void test_long_stack_put_take() throws IOException {
        engine.lock.writeLock().lock();

        final long max = 150;
        for(long i=1;i<max;i++){
            engine.longStackPut(TEST_LS_RECID, i);
        }

        for(long i = max-1;i>0;i--){
            assertEquals(i, engine.longStackTake(TEST_LS_RECID));
        }

        assertEquals(0, getLongStack(TEST_LS_RECID).size());

    }

    @Test public void test_long_stack_put_take_simple() throws IOException {
        engine.lock.writeLock().lock();
        engine.longStackPut(TEST_LS_RECID, 111);
        assertEquals(111L, engine.longStackTake(TEST_LS_RECID));
    }


    @Test public void test_basic_long_stack() throws IOException {
        //dirty hack to make sure we have lock
        engine.lock.writeLock().lock();
        final long max = 150;
        ArrayList<Long> list = new ArrayList<Long>();
        for(long i=1;i<max;i++){
            engine.longStackPut(TEST_LS_RECID, i);
            list.add(i);
        }

        Collections.reverse(list);
        commit();

        assertEquals(list, getLongStack(TEST_LS_RECID));
    }


    @Test public void long_stack_page_created_after_put() throws IOException {
        engine.lock.writeLock().lock();
        engine.longStackPut(TEST_LS_RECID, 111);
        commit();
        long pageId = engine.index.getLong(TEST_LS_RECID*8);
        assertEquals(StorageDirect.LONG_STACK_PAGE_SIZE, pageId>>>48);
        pageId = pageId & StorageDirect.PHYS_OFFSET_MASK;
        assertEquals(8L, pageId);
        assertEquals(1, engine.phys.getUnsignedByte(pageId));
        assertEquals(0, engine.phys.getLong(pageId)& StorageDirect.PHYS_OFFSET_MASK);
        assertEquals(111, engine.phys.getLong(pageId+8));
    }

    @Test public void long_stack_put_five() throws IOException {
        engine.lock.writeLock().lock();
        engine.longStackPut(TEST_LS_RECID, 111);
        engine.longStackPut(TEST_LS_RECID, 112);
        engine.longStackPut(TEST_LS_RECID, 113);
        engine.longStackPut(TEST_LS_RECID, 114);
        engine.longStackPut(TEST_LS_RECID, 115);

        commit();
        long pageId = engine.index.getLong(TEST_LS_RECID*8);
        assertEquals(StorageDirect.LONG_STACK_PAGE_SIZE, pageId>>>48);
        pageId = pageId & StorageDirect.PHYS_OFFSET_MASK;
        assertEquals(8L, pageId);
        assertEquals(5, engine.phys.getUnsignedByte(pageId));
        assertEquals(0, engine.phys.getLong(pageId)& StorageDirect.PHYS_OFFSET_MASK);
        assertEquals(111, engine.phys.getLong(pageId+8));
        assertEquals(112, engine.phys.getLong(pageId+16));
        assertEquals(113, engine.phys.getLong(pageId+24));
        assertEquals(114, engine.phys.getLong(pageId+32));
        assertEquals(115, engine.phys.getLong(pageId+40));
    }

    @Test public void long_stack_page_deleted_after_take() throws IOException {
        engine.lock.writeLock().lock();
        engine.longStackPut(TEST_LS_RECID, 111);
        commit();
        assertEquals(111L, engine.longStackTake(TEST_LS_RECID));
        commit();
        assertEquals(0L, engine.index.getLong(TEST_LS_RECID*8));
    }

    @Test public void long_stack_page_overflow() throws IOException {
        engine.lock.writeLock().lock();
        //fill page until near overflow
        for(int i=0;i< StorageDirect.LONG_STACK_NUM_OF_RECORDS_PER_PAGE;i++){
            engine.longStackPut(TEST_LS_RECID, 1000L+i);
        }
        commit();

        //check content
        long pageId = engine.index.getLong(TEST_LS_RECID*8);
        assertEquals(StorageDirect.LONG_STACK_PAGE_SIZE, pageId>>>48);
        pageId = pageId & StorageDirect.PHYS_OFFSET_MASK;
        assertEquals(8L, pageId);
        assertEquals(StorageDirect.LONG_STACK_NUM_OF_RECORDS_PER_PAGE, engine.phys.getUnsignedByte(pageId));
        for(int i=0;i< StorageDirect.LONG_STACK_NUM_OF_RECORDS_PER_PAGE;i++){
            assertEquals(1000L+i, engine.phys.getLong(pageId+8+i*8));
        }

        //add one more item, this will trigger page overflow
        engine.longStackPut(TEST_LS_RECID, 11L);
        commit();
        //check page overflowed
        pageId = engine.index.getLong(TEST_LS_RECID*8);
        assertEquals(StorageDirect.LONG_STACK_PAGE_SIZE, pageId>>>48);
        pageId = pageId & StorageDirect.PHYS_OFFSET_MASK;
        assertEquals(8L+ StorageDirect.LONG_STACK_PAGE_SIZE, pageId);
        assertEquals(1, engine.phys.getUnsignedByte(pageId));
        assertEquals(8L, engine.phys.getLong(pageId)& StorageDirect.PHYS_OFFSET_MASK);
        assertEquals(11L, engine.phys.getLong(pageId+8));
    }


    @Test public void test_freePhysRecSize2FreeSlot_asserts(){
        if(!CC.ASSERT) return;
        try{
            engine.freePhysRecSize2FreeSlot(StorageDirect.MAX_RECORD_SIZE + 1);
            fail();
        }catch(IllegalArgumentException e){
            //expected
        }

        try{
            engine.freePhysRecSize2FreeSlot(-1);
            fail();
        }catch(IllegalArgumentException e){
            //expected
        }
    }

    @Test public void test_freePhysRecSize2FreeSlot_incremental(){
        int oldSlot = -1;
        for(int size = 1;size<= StorageDirect.MAX_RECORD_SIZE;size++){
            int slot = engine.freePhysRecSize2FreeSlot(size);
            assertTrue(slot >= 0);
            assertTrue(oldSlot <= slot);
            assertTrue(slot - oldSlot <= 1);
            oldSlot= slot;
        }
        assertEquals(StorageDirect.NUMBER_OF_PHYS_FREE_SLOT - 1, oldSlot);
    }

    @Test public void test_freePhysRecSize2FreeSlot_max_size_has_unique_slot(){
        int slotMax = engine.freePhysRecSize2FreeSlot(StorageDirect.MAX_RECORD_SIZE);
        int slotMaxMinus1 = engine.freePhysRecSize2FreeSlot(StorageDirect.MAX_RECORD_SIZE - 1);
        assertEquals(slotMax, slotMaxMinus1 + 1);
    }

    @Test  public void test_freePhys_PutAndTake() throws IOException {
        engine.lock.writeLock().lock();

        final long offset = 1111000;
        final int size = 344;
        final long indexVal =(((long)size) <<48) |offset;

        engine.freePhysRecPut(indexVal);

        assertEquals(indexVal, engine.freePhysRecTake(size));
        assertEquals(arrayList(), getLongStack(StorageDirect.RECID_FREE_PHYS_RECORDS_START + engine.freePhysRecSize2FreeSlot(size)));
    }

    //TODO test randomly put and delete values


    @Test public void test_2GB_over() throws IOException {
        Assume.assumeTrue(CC.FULL_TEST);

       byte[] data = new byte[51111];
       int dataHash = Arrays.hashCode(data);

        Set<Long> recids = new TreeSet<Long>();

        for(int i = 0; i<1e5;i++){
            long recid = engine.recordPut(data, Serializer.BYTE_ARRAY_SERIALIZER);
            recids.add(recid);

//            if(i%10000==0){
//            System.out.println(recid);
//            for(Long l:recids){
//                byte[] b = engine.recordGet(l, Serializer.BYTE_ARRAY_SERIALIZER);
//                int hash = Arrays.hashCode(b);
//                assertEquals(l,dataHash, hash);
//            }
//            }

        }

        engine.commit();



        for(Long l:recids){
            byte[] b = engine.recordGet(l, Serializer.BYTE_ARRAY_SERIALIZER);
            int hash = Arrays.hashCode(b);
            assertEquals(dataHash, hash);
        }

    }


    @Test public void test_store_reopen(){
        long recid = engine.recordPut("aaa", Serializer.STRING_SERIALIZER);

        engine.commit();
        reopenStore();

        String aaa = engine.recordGet(recid, Serializer.STRING_SERIALIZER);
        assertEquals("aaa",aaa);
    }

    @Test  public void test_store_reopen_over_2GB(){
        Assume.assumeTrue(CC.FULL_TEST);

        byte[] data = new byte[11111];
        final long max = ByteBuffer2.BUF_SIZE*2L/data.length;
        final int hash = Arrays.hashCode(data);

        List<Long> recids = new ArrayList<Long>();

        for(int i = 0;i<max;i++){
            long recid = engine.recordPut(data,Serializer.BYTE_ARRAY_SERIALIZER);
            recids.add(recid);
        }

        reopenStore();

        for(long recid:recids){
            byte[] b  = engine.recordGet(recid, Serializer.BYTE_ARRAY_SERIALIZER);
            assertEquals(hash,Arrays.hashCode(b));
        }
    }

    @Test public void in_memory_test(){
        StorageDirect engine = new StorageDirect(null);
        Map<Long, Integer> recids = new HashMap<Long,Integer>();
        for(int i = 0;i<1000;i++){
            long recid = engine.recordPut(i, Serializer.BASIC_SERIALIZER);
            recids.put(recid, i);
        }
        for(Long recid: recids.keySet()){
            assertEquals(recids.get(recid), engine.recordGet(recid, Serializer.BASIC_SERIALIZER));
        }
    }




}
