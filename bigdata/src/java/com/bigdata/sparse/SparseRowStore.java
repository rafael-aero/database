/*

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
package com.bigdata.sparse;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.bigdata.btree.BatchInsert;
import com.bigdata.btree.IEntryIterator;
import com.bigdata.btree.IIndex;
import com.bigdata.btree.IKeyBuilder;
import com.bigdata.btree.SuccessorUtil;
import com.bigdata.service.IDataService;

/**
 * A client-side class that knows how to use an {@link IIndex} to provide an
 * efficient data model in which a logical row is stored as one or more entries
 * in the {@link IIndex}. Operations are provided for atomic read and write of
 * logical row. While the scan operations are always consistent (they will never
 * reveal data from a row that undergoing concurrent modification), they do NOT
 * cause concurrent atomic row writes to block. This means that rows that would
 * be visited by a scan MAY be modified before the scan reaches those rows and
 * the client will see the updates.
 * <p>
 * The {@link SparseRowStore} requires that you declare the {@link KeyType} for
 * primary key so that it may impose a consistent total ordering over the
 * generated keys in the index.
 * <p>
 * There is no intrinsic reason why column values must be strongly typed.
 * Therefore, by default column values are loosely typed. However, column values
 * MAY be constrained by a {@link Schema}.
 * <p>
 * Note: Instances of this class are NOT thread-safe since the
 * {@link #keyBuilder} used by an instance is not thread-safe.
 * <p>
 * This class builds keys using the sparse row store design pattern. Each
 * logical row is modeled as an ordered set of BTree entries whose keys are
 * formed as:
 * </p>
 * 
 * <pre>
 *       
 *       [schemaName][primaryKey][columnName][timestamp]
 *       
 * </pre>
 * 
 * <p>
 * 
 * and the values are the value for a given column for that primary key.
 * 
 * </p>
 * 
 * <p>
 * 
 * Timestamps are either generated by the application, in which case they define
 * the semantics of a write-write conflict, or on write by the index. In the
 * latter case, write-write conflicts never arise. Regardless of how timestamps
 * are generated, the use of the timestamp in the <em>key</em> requires that
 * applications specify filters that are applied during row scans to limit the
 * data points actually returned as part of the row. For example, only returning
 * the most recent column values no later than a given timestamp for all columns
 * for some primary key.
 * 
 * </p>
 * 
 * <p>
 * 
 * For example, assuming records with the following columns
 * 
 * <ul>
 * <li>Id</li>
 * <li>Name</li>
 * <li>Employer</li>
 * <li>DateOfHire</li>
 * </ul>
 * 
 * would be represented as a series of index entries as follows:
 * 
 * </p>
 * 
 * <pre>
 *       
 *       [employee][12][DateOfHire][t0] : [4/30/02]
 *       [employee][12][DateOfHire][t1] : [4/30/05]
 *       [employee][12][Employer][t0]   : [SAIC]
 *       [employee][12][Employer][t1]   : [SYSTAP]
 *       [employee][12][Id][t0]         : [12]
 *       [employee][12][Name][t0]       : [Bryan Thompson]
 *       
 * </pre>
 * 
 * <p>
 * 
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @todo Do not require unicode support for column names or values?
 * 
 * @todo disallow nul bytes in the schema and column names.
 * 
 * @todo We do not have a means to decode a primary key that is Unicode (or
 *       variable length). The problem is that the #of bytes in the primary key
 *       needs to be part of the overall key itself but that will distort the
 *       total key ordering unless it is VERY cleverly done.
 * 
 * @todo support byte[] as a primary key type.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class SparseRowStore {

    /**
     * Log for btree opeations.
     */
    protected static final Logger log = Logger.getLogger(SparseRowStore.class);

    /**
     * True iff the {@link #log} level is INFO or less.
     */
    final protected boolean INFO = log.getEffectiveLevel().toInt() <= Level.INFO
            .toInt();

    /**
     * True iff the {@link #log} level is DEBUG or less.
     */
    final protected boolean DEBUG = log.getEffectiveLevel().toInt() <= Level.DEBUG
            .toInt();

    static final String UTF8 = "UTF-8";
    
    private final IIndex ndx;
    
    // FIXME Thread safety.
    final IKeyBuilder keyBuilder;
    
    private final Schema schema;
    
    /**
     * The value which indicates that the timestamp will be assigned by the
     * server.
     */
    public static final long AUTO_TIMESTAMP = -1L;

    /**
     * Return the {@link Schema} used by the {@link SparseRowStore}.
     */
    public Schema getSchema() {
        
        return schema;
        
    }
    
    /**
     * Create a client-side abstraction that treats and {@link IIndex} as a
     * {@link SparseRowStore}.
     * 
     * @param ndx
     *            The index.
     * @param keyBuilder
     *            Used to construct keys for the index.
     * @param schema
     *            The schema that defines how keys will be encoded.
     */
    public SparseRowStore(IIndex ndx, IKeyBuilder keyBuilder, Schema schema) {
        
        if(ndx==null) throw new IllegalArgumentException();

        if(keyBuilder==null) throw new IllegalArgumentException();
        
        if(schema==null) throw new IllegalArgumentException();
        
        this.ndx = ndx;
        
        this.keyBuilder = keyBuilder;
       
        this.schema = schema;
        
    }

    /**
     * Read the most recent logical row from the index.
     * 
     * @param primaryKey
     *            The primary key that identifies the logical row.
     *            
     * @return The data in that row -or- <code>null</code> if there was no row
     *         for that primary key.
     */
    public Map<String,Object> read(Object primaryKey) {

        return read( primaryKey, -1L );

    }
    
    /**
     * Read a logical row from the index.
     * 
     * @param primaryKey
     *            The primary key that identifies the logical row.
     * 
     * @param timestamp
     *            The logical row having a timestamp not greater than this value
     *            will be retrieved. In particular, older revisions of a column
     *            value will be overwritten by more recent revisions of that
     *            column value before the row is returned (revisions may include
     *            the deletion of a column value, which is marked as a null
     *            column value in the index). When -1L, the most recent logical
     *            row will be retrieved.
     * 
     * @return The data in that row -or- <code>null</code> if there was no row
     *         for that primary key.
     * 
     * FIXME Support an optional filter to see only certain columns or revisions --
     * this needs to be downloadable code that can be used in
     * {@link IDataService#rangeQuery(long, String, int, byte[], byte[], int, int)}
     * and {@link IIndex} needs to support passing through that filter.
     * 
     * @todo the timestamp is not returned to the caller. it could be set by
     *       side effect or using a member field. (in the general case of course
     *       each column value has its own timestamp and the map needs to store
     *       a list of timestamped column values for each column or some such
     *       absurdity).
     */
    public Map<String,Object> read(Object primaryKey, long timestamp) {
        
        byte[] fromKey = fromKey(primaryKey).getKey(); 

        byte[] toKey = toKey(primaryKey).getKey();
        
        if (DEBUG) {
            log.info("read: fromKey=" + Arrays.toString(fromKey));
            log.info("read:   toKey=" + Arrays.toString(toKey));
        }
        
        /*
         * range query (scan).
         */
        IEntryIterator itr = ndx.rangeIterator(fromKey, toKey);
        
        Map<String,Object> map = new HashMap<String,Object>(); 
        
        while(itr.hasNext()) {
            
            byte[] val = (byte[]) itr.next();
            
            byte[] key = itr.getKey();
            
            /*
             * Decode the key so that we can get the column name. We have the
             * advantage of knowing the last byte in the primary key. Since the
             * fromKey was formed as [schema][primaryKey], the length of the
             * fromKey is the index of the 1st byte in the column name.
             */
            KeyDecoder keyDecoder = new KeyDecoder(schema,key,fromKey.length);

            // The column name.
            final String col = keyDecoder.col;
            
            /*
             * If a timestamp target was given, then skip column values having a
             * timestamp strictly greater than the given value.
             * 
             * @todo this should be done server side in the filter so that only
             * relevant index entries are returned to the client.
             */
            if (timestamp != -1L) {

                final long columnValueTimestamp = keyDecoder.getTimestamp();

                if (columnValueTimestamp > timestamp) {

                    if (DEBUG)
                        log.info("Ignoring newer revision: col=" + col
                                + ", timestamp=" + columnValueTimestamp);
                    
                    continue;

                }

            }
            
            /*
             * decode the value.
             */
            
            Object v = ValueType.decode(val);
            
            /*
             * Add to the map representing the row.
             * 
             * Note: This will overwrite revisions of the same column value with
             * an earlier timestamp.
             * 
             * Note: A write of a [null] column value will be made persistent in
             * the index. In order for that [null] to have the effect of
             * removing the entry from the map we MUST explicitly test for that
             * here.
             * 
             * @todo if filtering for certain column names or revisions then we
             * need to impose that filtering here.
             */
            
            if(v == null) {
             
                /*
                 * We have found a null column value, so we need to remove an
                 * older revision if one exists.
                 */

                Object oldValue = map.remove(col);
                
                if( oldValue != null) {
                
                    if (DEBUG)
                        log.debug("Removing revision for " + col
                                + " from the row (was " + oldValue + ").");
                    
                }
                                
            } else {

                /*
                 * Insert the column value, potentially overwriting an older
                 * revision.
                 */
                
                Object oldValue = map.put(col, v);
                
                if( oldValue != null ) {
                    
                    if (DEBUG)
                        log.debug("Overwriting revision for " + col
                                + " from the row (was " + oldValue + ", now "
                                + v + ")");
                    
                }
                
            }
            
        }

        if(map.size()==0) {
            
            /*
             * Return null iff there are no column values for that primary key.
             */
            
            if (DEBUG)
                log.debug("No row for primaryKey: " + primaryKey);
            
            return null;
            
        }
        
        return map;
        
    }
    
    /**
     * Inserts or updates a row in the store.
     * <p>
     * Note: In order to cause a column value for row to be deleted you MUST
     * specify a <code>null</code> column value for that column.
     * 
     * @param row
     *            The column names and values for that row.
     * @param timestamp
     *            The timestamp to use for the row -or-
     *            <code>#AUTO_TIMESTAMP</code> if the timestamp will be
     *            auto-generated by the data service.
     * 
     * @return The timestamp assigned to the row. If the caller specified a
     *         timestamp, then this will be that value. If the timestamp was
     *         assigned by the server, then this will be that value.
     */
    public long write(Map<String,Object> row, long timestamp) {
        
        BatchInsert op = encode(row,timestamp);
        
        ndx.insert(op);
        
        // @todo return timestamp assigned by the server.
        return timestamp;
        
    }

    /**
     * Encode a write operation.
     * 
     * @param row
     *            The logical row.
     * @param timestamp
     *            The timestamp to be used for the column value revisions in
     *            that row -or- {@link #AUTO_TIMESTAMP} iff the timestamps will
     *            be assigned by the server.
     * 
     * @return The batch operation that will write the row on the index.
     */
    public BatchInsert encode(Map<String,Object> row, long timestamp) {

        if (timestamp == AUTO_TIMESTAMP) {

            /*
             * FIXME this needs to be done server-side during the unisolated
             * operation.
             * 
             * @todo It should not be a distinct timestamp if we allow
             * concurrent unisolated operations in the same commit group to
             * overwrite one another (alternatively, the behavior here could be
             * governed by the metadata for the sparse row store index so some
             * applications could insist on distrint timestamps).
             */

            timestamp = System.currentTimeMillis();

        }

        /*
         * Get the column value that corresponds to the primary key for this
         * row.
         */
        final Object primaryKey = row.get(schema.getPrimaryKey());

        if (primaryKey == null) {

            throw new IllegalArgumentException("Primary key required: "
                    + schema.getPrimaryKey());
            
        }

        log.info("Schema=" + schema + ", primaryKey=" + schema.getPrimaryKey()
                + ", value=" + primaryKey);
        
        // force the row into order by column name.
        if(! (row instanceof SortedMap)) {

            /*
             * @todo always do this to force override of column name comparator?
             * If we do not always override then it is possible that an
             * alternative comparator could have been given and the batch
             * operation will not be fully ordered, resulting in a (slight)
             * inefficiency during the operations on the index.
             */
            row = new TreeMap<String,Object>(row);
            
        }
        
        final Iterator<Map.Entry<String,Object>> itr = row.entrySet().iterator();

        final int ntuples = row.size();
        
        final byte[][] keys = new byte[ntuples][];
        final byte[][] vals = new byte[ntuples][];
        
        int i = 0;
        
        while(itr.hasNext()) {
            
            Map.Entry<String, Object> entry = itr.next();
            
            String col = entry.getKey();

            // validate the column name production.
            NameChecker.assertColumnName(col);
            
            Object val = entry.getValue();
            
            // format the schema name and the primary key into the key builder.
            fromKey(primaryKey);
            
            /*
             * The column name. Note that the column name is NOT stored with
             * Unicode compression so that we can decode it without loss.
             */
            try {
                
                keyBuilder.append(col.getBytes(UTF8)).appendNul();
                
            } catch(UnsupportedEncodingException ex) {
                
                throw new RuntimeException(ex);
                
            }
            
            /*
             * @todo support auto timestamp vs application timestamp.
             * 
             * When auto-timestamping is used, a timestamp must be assigned by
             * the data service, which needs to append the timestamp for the
             * atomic row write.
             * 
             * Note: Timestamps can be locally generated on the server since
             * they must be consistent solely within a row, and all revisions of
             * column values for the same row will always be in the same index
             * partition and hence on the same server. The only way in which
             * time could go backward is if there is a failover to another
             * server for the partition and the other server has a different
             * clock time.
             * 
             * Note: Timestamp resolution can be either next nano, nanos, total
             * commit counters (a new datum for the root blocks that always
             * increments, rather than resetting to zero when a new store is
             * created - this would be safest since the total commit counter
             * will be consistent even across failovers), or
             * currentTimeMillis(). What is at stake is that revisions written
             * within the resolution of the timestamp assignment will cause
             * overwrites of existing revisions with the same timestamp rather
             * that causing new revisions with their own distinct timestamp to
             * be written.
             */
            keyBuilder.append(timestamp);

            keys[i] = keyBuilder.getKey();
            
            // encode the value.
            vals[i] = ValueType.encode( val );

            if (DEBUG)
                log.debug("write: key=" + Arrays.toString(keys[i]));

            i++;
            
        }

        BatchInsert op = new BatchInsert(keys.length,keys,vals);

        return op;
        
    }

    /**
     * Return an iterator that will visit each logical row that can be
     * reconstructed from a scan in the specified primary key range.
     * 
     * @param fromKey
     * @param toKey
     * @return
     * 
     * FIXME Implement scan (and tests).
     */
    public Iterator<Map<String,Object>> scan(Object fromKey, Object toKey) {
       
        throw new UnsupportedOperationException();
        
    }
    
    /**
     * Helper method appends a typed value to the compound key (this is used to
     * get the primary key into the compound key).
     * 
     * @param keyType
     *            The target data type.
     * @param v
     *            The value.
     * 
     * @return The {@link #keyBuilder}.
     * 
     * @todo support variable length byte[]s as a primary key type.
     * 
     * FIXME Verify that variable length primary keys do not cause problems in
     * the total ordering. Do we need a code (e.g., nul nul) that never occurs
     * in a valid primary key when the primary key can vary in length? The
     * problem occurs when the column name could become confused with the
     * primary key in comparisons owing to the lack of an unambiguous delimiter
     * and a variable length primary key. Another approach is to fill the
     * variable length primary key to a set length...
     */
    protected IKeyBuilder appendPrimaryKey(Object v, boolean successor) {
        
        KeyType keyType = schema.getPrimaryKeyType();
        
        if (successor) {
            
            switch (keyType) {

            case Integer:
                return keyBuilder.append(successor(((Number) v).intValue()));
            case Long:
                return keyBuilder.append(successor(((Number) v).longValue()));
            case Float:
                return keyBuilder.append(successor(((Number) v).floatValue()));
            case Double:
                return keyBuilder.append(successor(((Number) v).doubleValue()));
            case Unicode:
                return keyBuilder.appendText(v.toString(), true/*unicode*/, true/*successor*/);
            case ASCII:
                return keyBuilder.appendText(v.toString(), false/*unicode*/, true/*successor*/);
            case Date:
                return keyBuilder.append(successor(((Date) v).getTime()));
            }
            
        } else {
            
            switch (keyType) {

            case Integer:
                return keyBuilder.append(((Number) v).intValue());
            case Long:
                return keyBuilder.append(((Number) v).longValue());
            case Float:
                return keyBuilder.append(((Number) v).floatValue());
            case Double:
                return keyBuilder.append(((Number) v).doubleValue());
            case Unicode:
                return keyBuilder.appendText(v.toString(),true/*unicode*/,false/*successor*/);
            case ASCII:
                return keyBuilder.appendText(v.toString(),true/*unicode*/,false/*successor*/);
            case Date:
                return keyBuilder.append(((Date) v).getTime());
            }
            
        }

        return keyBuilder;
        
    }
 
    /**
     * Return the successor of a primary key object.
     * 
     * @param v
     *            The object.
     * 
     * @return The successor.
     * 
     * @throws UnsupportedOperationException
     *             if the primary key type is {@link KeyType#Unicode}. See
     *             {@link #toKey(Object)}, which correctly forms the successor
     *             key in all cases.
     */
    private Object successor(Object v) {
        
        KeyType keyType = schema.getPrimaryKeyType();
        
        switch(keyType) {

        case Integer:
            return SuccessorUtil.successor(((Number)v).intValue());
        case Long:
            return SuccessorUtil.successor(((Number)v).longValue());
        case Float:
            return SuccessorUtil.successor(((Number)v).floatValue());
        case Double:
            return SuccessorUtil.successor(((Number)v).doubleValue());
        case Unicode:
        case ASCII:
            /*
             * Note: See toKey() for how to correctly form the sort key for the
             * successor of a Unicode value.
             */
            throw new UnsupportedOperationException();
//            return SuccessorUtil.successor(v.toString());
//            return SuccessorUtil.successor(v.toString());
        case Date:
            return SuccessorUtil.successor(((Date)v).getTime());
        }

        return keyBuilder;
        
    }
    
    /**
     * Forms the key in {@link #keyBuilder} that should be used as the first key
     * (inclusive) for a range query that will visit all index entries for the
     * specified primary key.
     * 
     * @param primaryKey
     *            The primary key.
     * 
     * @return The {@link #keyBuilder}, which will have the schema and the
     *         primary key already formatted in its buffer.
     */
    protected IKeyBuilder fromKey(Object primaryKey) {
        
        keyBuilder.reset();

        // append the (encoded) schema name.
        keyBuilder.append(schema.getSchemaBytes());
        
        // append the (encoded) primary key.
        appendPrimaryKey(primaryKey,false/*successor*/);
        
        return keyBuilder;

    }

    /**
     * Forms the key in {@link #keyBuilder} that should be used as the last key
     * (exclusive) for a range query that will visit all index entries for the
     * specified primary key.
     * 
     * @param primaryKey
     *            The primary key.
     * 
     * @return The {@link #keyBuilder}, which will have the schema and the
     *         successor of the primary key already formatted in its buffer.
     */
    protected IKeyBuilder toKey(Object primaryKey) {
        
        keyBuilder.reset();

        // append the (encoded) schema name.
        keyBuilder.append(schema.getSchemaBytes());

        // append successor of the (encoded) primary key.
        appendPrimaryKey(primaryKey, true/*successor*/);

        return keyBuilder;

    }
    
}
