package org.opendedup.collections;

import java.io.IOException;
import java.io.SyncFailedException;

import org.opendedup.sdfs.filestore.ChunkData;
import org.opendedup.util.LargeBloomFilter;


public interface AbstractShard {

	public abstract void iterInit();

	public abstract byte[] nextKey() throws IOException;

	public abstract long getBigestKey() throws IOException;

	/**
	 * initializes the Object set of this hash table.
	 * 
	 * @param initialCapacity
	 *            an <code>int</code> value
	 * @return an <code>int</code> value
	 * @throws IOException
	 */
	public abstract long setUp() throws IOException;

	/**
	 * Searches the set for <tt>obj</tt>
	 * 
	 * @param obj
	 *            an <code>Object</code> value
	 * @return a <code>boolean</code> value
	 */
	public abstract boolean containsKey(byte[] key);

	/**
	 * Searches the set for <tt>obj</tt>
	 * 
	 * @param obj
	 *            an <code>Object</code> value
	 * @return a <code>boolean</code> value
	 * @throws KeyNotFoundException
	 */
	public abstract boolean isClaimed(byte[] key) throws KeyNotFoundException,
			IOException;

	public abstract boolean update(byte[] key, long value) throws IOException;

	public abstract boolean remove(byte[] key) throws IOException;

	public abstract boolean put(ChunkData cm)
			throws HashtableFullException, IOException;
	
	public abstract boolean put(byte [] key,long val)
			throws HashtableFullException, IOException;

	public abstract int getEntries();

	public abstract long get(byte[] key);

	public abstract long get(byte[] key, boolean claim);

	public abstract int size();

	public abstract void close();

	public abstract long claimRecords() throws IOException;

	public abstract long claimRecords(LargeBloomFilter bf)
			throws IOException;

	public abstract void sync() throws SyncFailedException, IOException;


}