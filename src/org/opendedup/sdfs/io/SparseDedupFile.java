package org.opendedup.sdfs.io;

import java.io.File;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.collections.HashtableFullException;
import org.opendedup.collections.LargeLongByteArrayMap;
import org.opendedup.collections.LongByteArrayMap;
import org.opendedup.hashing.AbstractHashEngine;
import org.opendedup.hashing.HashFunctionPool;
import org.opendedup.hashing.ThreadPool;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.filestore.DedupFileStore;
import org.opendedup.sdfs.servers.HCServiceProxy;
import org.opendedup.util.DeleteDir;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

public class SparseDedupFile implements DedupFile {

	private ArrayList<DedupFileLock> locks = new ArrayList<DedupFileLock>();
	private String GUID = "";
	private transient final MetaDataDedupFile mf;
	private transient final ArrayList<DedupFileChannel> buffers = new ArrayList<DedupFileChannel>();
	private transient String databasePath = null;
	private transient String databaseDirPath = null;
	private transient String chunkStorePath = null;
	private DedupFileChannel staticChannel = null;
	public long lastSync = 0;
	LongByteArrayMap bdb = null;
	MessageDigest digest = null;
	public static final HashFunctionPool hashPool = new HashFunctionPool(
			Main.writeThreads + 1);
	protected static transient final ThreadPool pool = new ThreadPool(
			Main.writeThreads + 1, 1024);
	private final ReentrantLock channelLock = new ReentrantLock();
	private final ReentrantLock initLock = new ReentrantLock();
	private final ReentrantLock syncLock = new ReentrantLock();
	private final ReentrantLock writeLock = new ReentrantLock();
	private LargeLongByteArrayMap chunkStore = null;
	private int maxWriteBuffers = ((Main.maxWriteBuffers * 1024 * 1024) / Main.CHUNK_LENGTH) + 1;
	private transient final ConcurrentHashMap<Long, DedupChunkInterface> flushingBuffers = new ConcurrentHashMap<Long, DedupChunkInterface>(
			1024, .75f);

	LoadingCache<Long, DedupChunkInterface> writeBuffers = CacheBuilder
			.newBuilder().maximumSize(maxWriteBuffers)
			.expireAfterAccess(2, TimeUnit.SECONDS)
			.concurrencyLevel(Main.writeThreads * 3)
			.removalListener(new RemovalListener<Long, DedupChunkInterface>() {
				public void onRemoval(
						RemovalNotification<Long, DedupChunkInterface> removal) {
					DedupChunkInterface ck = removal.getValue();
					Long pos = removal.getKey();
					try {
						ck.flush();
					} catch (BufferClosedException e) {
						SDFSLogger.getLog().error(
								"Error while closing buffer at " + pos);
					}
					// flushingBuffers.put(pos, ck);
				}
			}).build(new CacheLoader<Long, DedupChunkInterface>() {
				public DedupChunkInterface load(Long key) throws IOException,
						FileClosedException {
					if (closed) {
						throw new FileClosedException("file already closed");
					}
					long chunkPos = getChuckPosition(key);
					DedupChunkInterface writeBuffer = null;
					writeBuffer = flushingBuffers.remove(chunkPos);
					if (writeBuffer == null) {
						writeBuffer = marshalWriteBuffer(chunkPos, false);
					}

					writeBuffer.open();

					return writeBuffer;
				}
			});

	/*
	 * @SuppressWarnings("serial") private final transient LRUMap writeBuffers =
	 * new LRUMap( maxWriteBuffers + 1, false) {
	 * 
	 * @Override protected boolean removeLRU(AbstractLinkedMap.LinkEntry eldest)
	 * { if (size() >= maxWriteBuffers) { WritableCacheBuffer writeBuffer =
	 * (WritableCacheBuffer) eldest .getValue(); if (writeBuffer != null) { try
	 * { writeBuffer.flush(); } catch (Exception e) { SDFSLogger.getLog().debug(
	 * "while closing position " + writeBuffer.getFilePosition(), e); } } }
	 * return true; } };
	 */
	private boolean closed = true;
	static {
		File f = new File(Main.dedupDBStore);
		if (!f.exists())
			f.mkdirs();
	}

	public static synchronized void flushThreadPool() {
		pool.flush();
	}

	public SparseDedupFile(MetaDataDedupFile mf) throws IOException {
		// SDFSLogger.getLog().info("Using LRU Max WriteBuffers=" +
		// this.maxWriteBuffers);
		SDFSLogger.getLog().debug("dedup file opened for " + mf.getPath());
		SDFSLogger.getLog().debug("LRU Size is " + (maxWriteBuffers + 1));
		this.mf = mf;
		if (mf.getDfGuid() == null) {
			// new Instance
			this.GUID = UUID.randomUUID().toString();
		} else {
			this.GUID = mf.getDfGuid();
		}
	}

	public void removeFromFlush(long pos) {
		this.flushingBuffers.remove(pos);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @seecom.annesam.sdfs.io.AbstractDedupFile#snapshot(com.annesam.sdfs.io.
	 * MetaDataDedupFile)
	 */
	@Override
	public DedupFile snapshot(MetaDataDedupFile snapmf) throws IOException,
			HashtableFullException {
		return this.snapshot(snapmf, true);
	}

	@Override
	public DedupFile snapshot(MetaDataDedupFile snapmf, boolean propigate)
			throws IOException, HashtableFullException {
		DedupFileChannel ch = null;
		DedupFileChannel _ch = null;
		SparseDedupFile _df = null;
		try {
			ch = this.getChannel(-1);
			this.sync(true);
			_df = new SparseDedupFile(snapmf);
			File _directory = new File(Main.dedupDBStore + File.separator
					+ _df.GUID.substring(0, 2) + File.separator + _df.GUID);
			File _dbf = new File(_directory.getPath() + File.separator
					+ _df.GUID + ".map");
			File _dbc = new File(_directory.getPath() + File.separator
					+ _df.GUID + ".chk");
			SDFSLogger.getLog().debug("Snap folder is " + _directory);
			SDFSLogger.getLog().debug("Snap map is " + _dbf);
			SDFSLogger.getLog().debug("Snap chunk is " + _dbc);
			bdb.copy(_dbf.getPath());
			chunkStore.copy(_dbc.getPath());

			snapmf.setDedupFile(_df);
			return _df;
		} catch (Exception e) {
			SDFSLogger.getLog().warn("unable to clone file " + mf.getPath(), e);
			throw new IOException("unable to clone file " + mf.getPath(), e);
		} finally {
			if (ch != null)
				this.unRegisterChannel(ch, -1);
			if (_ch != null)
				_df.unRegisterChannel(_ch, -1);
			ch = null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @seecom.annesam.sdfs.io.AbstractDedupFile#snapshot(com.annesam.sdfs.io.
	 * MetaDataDedupFile)
	 */
	@Override
	public void copyTo(String path) throws IOException {
		this.copyTo(path, true);
	}

	@Override
	public void copyTo(String path, boolean propigate) throws IOException {
		DedupFileChannel ch = null;
		File dest = new File(path + File.separator + "ddb" + File.separator
				+ this.GUID.substring(0, 2) + File.separator + this.GUID);
		dest.mkdirs();
		try {
			ch = this.getChannel(-1);
			this.writeCache();
			this.sync(true);
			bdb.copy(dest.getPath() + File.separator + this.GUID + ".map");
			chunkStore.copy(dest.getPath() + File.separator + this.GUID
					+ ".chk");
		} catch (Exception e) {
			SDFSLogger.getLog().warn("unable to copy to" + mf.getPath(), e);
			throw new IOException("unable to clone file " + mf.getPath(), e);
		} finally {
			if (ch != null) {
				this.unRegisterChannel(ch, -1);
			}
		}
	}

	private boolean overLaps(long pos, long sz, boolean sharedRequested) {
		for (int i = 0; i < locks.size(); i++) {
			DedupFileLock lock = locks.get(i);
			if (lock.overLaps(pos, sz) && lock.isValid()) {
				if (!lock.isShared() || !sharedRequested)
					return true;
			}
		}
		return false;
	}

	@Override
	public boolean delete() {
		return this.delete(true);
	}

	@Override
	public boolean delete(boolean propigate) {
		this.channelLock.lock();
		this.syncLock.lock();
		this.initLock.lock();
		try {
			this.forceClose();
			String filePath = Main.dedupDBStore + File.separator
					+ this.GUID.substring(0, 2) + File.separator + this.GUID;
			DedupFileStore.removeOpenDedupFile(this.GUID);
			return DeleteDir.deleteDirectory(new File(filePath));
		} catch (Exception e) {

		} finally {
			this.channelLock.unlock();
			this.syncLock.unlock();
			this.initLock.unlock();
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#isClosed()
	 */
	@Override
	public boolean isClosed() {
		return this.closed;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#writeCache()
	 */
	@Override
	public int writeCache() throws IOException, HashtableFullException {
		this.writeLock.lock();
		try {
			Object[] buffers = null;
			SDFSLogger.getLog().debug(
					"Flushing Cache of for " + mf.getPath() + " of size "
							+ this.writeBuffers.size());
			this.writeBuffers.invalidateAll();
			int z = 0;
			buffers = this.flushingBuffers.keySet().toArray();
			for (int i = 0; i < buffers.length; i++) {
				DedupChunkInterface buf = this.flushingBuffers.get(buffers[i]);
				try {
					if (buf != null)
						buf.close();
				} catch (Exception e) {
					SDFSLogger.getLog().debug(
							"while closing position " + buf.getFilePosition(),
							e);
				} finally {

				}
				z++;
			}
			return z;
		} finally {
			this.writeLock.unlock();
		}

	}

	@Override
	public void writeCache(DedupChunkInterface writeBuffer) throws IOException,
			HashtableFullException, FileClosedException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		if (writeBuffer == null)
			return;
		if (writeBuffer.isDirty()) {
			try {
				byte[] hashloc = null;
				boolean doop = false;
				byte [] hash = null;
				if (writeBuffer.isBatchProcessed()) {
					hash = writeBuffer.getHash();
					hashloc = HCServiceProxy.writeChunk(hash, writeBuffer.getFlushedBuffer(), writeBuffer.getHashLoc());
				} else {
					AbstractHashEngine hc = hashPool.borrowObject();
					try {
						hash = hc.getHash(writeBuffer.getFlushedBuffer());
					} catch (Exception e) {
						throw new IOException(e);
					} finally {
						hashPool.returnObject(hc);
					}
					

					hashloc = HCServiceProxy.writeChunk(hash,
							writeBuffer.getFlushedBuffer(),
							writeBuffer.getLength(), writeBuffer.capacity(),
							mf.isDedup());
					

				}
				if (hashloc[1] == 0)
					throw new IOException(
							"unable to write chunk hash location at 1 = "
									+ hashloc[1]);
				if (hashloc[0] == 1)
					doop = true;
				writeBuffer.setHashLoc(hashloc);
				mf.getIOMonitor().addVirtualBytesWritten(
						writeBuffer.capacity(), true);
				if (!doop) {
					if (writeBuffer.isNewChunk()
							|| writeBuffer.isPrevDoop()) {
						mf.getIOMonitor().addActualBytesWritten(
								writeBuffer.capacity(), true);
					}
					if (writeBuffer.isPrevDoop()
							&& !writeBuffer.isNewChunk()) {
						mf.getIOMonitor().removeDuplicateBlock(true);
					}
				}

				this.updateMap(writeBuffer, hash, doop);
				if (doop && !writeBuffer.isPrevDoop())
					mf.getIOMonitor().addDulicateBlock(true);
			} catch (Exception e) {
				SDFSLogger.getLog().fatal(
						"unable to add chunk [" + writeBuffer.getHash()
								+ "] at position "
								+ writeBuffer.getFilePosition(), e);
			} finally {

			}

		}

	}

	@Override
	public void updateMap(DedupChunkInterface writeBuffer, byte[] hash,
			boolean doop) throws FileClosedException, IOException {
		this.updateMap(writeBuffer, hash, doop, true);
	}

	// private ReentrantLock updatelock = new ReentrantLock();
	@Override
	public void updateMap(DedupChunkInterface writeBuffer, byte[] hash,
			boolean doop, boolean propigate) throws FileClosedException,
			IOException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		SparseDataChunk chunk = null;
		try {
			// updatelock.lock();
			long filePosition = writeBuffer.getFilePosition();

			if (mf.isDedup() || doop) {
				chunk = new SparseDataChunk(doop, hash, false,
						writeBuffer.getHashLoc());
			} else {
				chunk = new SparseDataChunk(doop, hash, true,
						writeBuffer.getHashLoc());
				this.chunkStore.put(filePosition,
						writeBuffer.getFlushedBuffer());
			}
			bdb.put(filePosition, chunk.getBytes());
		} catch (Exception e) {
			SDFSLogger.getLog().fatal(
					"unable to write " + hash + " closing " + mf.getPath(), e);
			throw new IOException(e);
		} finally {
			chunk = null;
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getWriteBuffer(long)
	 */

	@Override
	public DedupChunkInterface getWriteBuffer(long position)
			throws IOException, FileClosedException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		long chunkPos = this.getChuckPosition(position);
		DedupChunkInterface writeBuffer = null;
		this.writeLock.lock();
		try {
			writeBuffer = (WritableCacheBuffer) this.writeBuffers.get(chunkPos);
		} catch (ExecutionException e) {
			throw new IOException(e);
		} finally {
			this.writeLock.unlock();
		}
		return writeBuffer;

	}

	private WritableCacheBuffer marshalWriteBuffer(long chunkPos,
			boolean newChunk) throws IOException, FileClosedException {
		DedupChunk ck = null;
		try {
			WritableCacheBuffer writeBuffer = null;

			if (newChunk)
				ck = createNewChunk(chunkPos);
			else
				ck = this.getHash(chunkPos, true);
			if (ck.isNewChunk()) {
				writeBuffer = new WritableCacheBuffer(ck.getHash(), chunkPos,
						ck.getLength(), this, ck.getHashLoc());
			} else {
				writeBuffer = new WritableCacheBuffer(ck, this);
				writeBuffer.setPrevDoop(ck.isDoop());
			}
			// need to fix this

			return writeBuffer;
		} finally {
			ck = null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getReadBuffer(long)
	 */
	@Override
	public DedupChunkInterface getReadBuffer(long position)
			throws FileClosedException, IOException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		long chunkPos = this.getChuckPosition(position);
		DedupChunkInterface writeBuffer = null;
		this.writeLock.lock();
		try {
			writeBuffer = (WritableCacheBuffer) this.writeBuffers.get(chunkPos);
		} catch (ExecutionException e) {
			throw new IOException(e);
		} finally {
			this.writeLock.unlock();
		}
		return writeBuffer;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getNumberofChunks()
	 */
	@Override
	public long getNumberofChunks() throws IOException, FileClosedException {
		if (this.closed) {
			throw new FileClosedException();
		}
		try {
			long count = -1;
			return count;
		} catch (Exception e) {
			SDFSLogger.getLog().warn(
					"Table does not exist in database " + this.GUID, e);
		} finally {
		}
		return -1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#sync()
	 */
	public void sync(boolean force) throws FileClosedException, IOException {
		this.sync(force, true);
	}

	@Override
	public void sync(boolean force, boolean propigate)
			throws FileClosedException, IOException {
		this.syncLock.lock();
		try {
			if (this.closed) {
				throw new FileClosedException("file already closed");

			}

			if (Main.safeSync) {
				try {
					this.writeCache();
					this.bdb.sync();
				} finally {
				}
			} else {
				try {

					/*
					 * Object[] buffers = null; this.writeBufferLock.lock(); try
					 * { SDFSLogger.getLog().debug( "Flushing Cache for " +
					 * mf.getPath() + " of size " + this.writeBuffers.size());
					 * if (this.writeBuffers.size() > 0) { buffers =
					 * this.writeBuffers.values().toArray(); } } finally {
					 * this.writeBufferLock.unlock(); } int z = 0; if (buffers
					 * != null) { for (int i = 0; i < buffers.length; i++) {
					 * WritableCacheBuffer buf = (WritableCacheBuffer)
					 * buffers[i]; try { buf.flush(); } catch
					 * (BufferClosedException e) { SDFSLogger.getLog().debug(
					 * "while closing position " + buf.getFilePosition(), e); }
					 * z++; } } SDFSLogger.getLog().debug("Flushed " + z +
					 * "with sync");
					 */
				} catch (Exception e) {
					throw new IOException(e);
				}
			}
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			this.syncLock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getChannel()
	 */
	@Override
	public DedupFileChannel getChannel(int flags) throws IOException {
		if (!Main.safeClose) {
			if (this.staticChannel == null) {
				if (this.isClosed())
					this.initDB();
				this.staticChannel = new DedupFileChannel(mf, -1);
			}
			return this.staticChannel;
		} else {
			channelLock.lock();
			try {
				if (this.isClosed() || this.buffers.size() == 0)
					this.initDB();
				DedupFileChannel channel = new DedupFileChannel(mf, flags);
				this.buffers.add(channel);
				return channel;
			} finally {
				channelLock.unlock();
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.annesam.sdfs.io.AbstractDedupFile#unRegisterChannel(com.annesam.sdfs
	 * .io.DedupFileChannel)
	 */
	@Override
	public void unRegisterChannel(DedupFileChannel channel, int flags) {
		if (Main.safeClose) {
			channelLock.lock();
			try {
				if (channel.getFlags() == flags) {
					this.buffers.remove(channel);
					channel.close(flags);
					if (this.buffers.size() == 0) {
						this.forceClose();
					}
				} else {
					SDFSLogger.getLog().warn(
							"unregister of filechannel for ["
									+ this.mf.getPath()
									+ "] failed because flags mismatch flags ["
									+ flags + "!=" + channel.getFlags() + "]");
				}
			} catch (Exception e) {
			} finally {
				channelLock.unlock();
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.annesam.sdfs.io.AbstractDedupFile#unRegisterChannel(com.annesam.sdfs
	 * .io.DedupFileChannel)
	 */
	@Override
	public void registerChannel(DedupFileChannel channel) throws IOException {
		if (!Main.safeClose) {
			channelLock.lock();
			try {
				if (this.staticChannel == null) {
					if (this.isClosed())
						this.initDB();
					this.staticChannel = channel;
				}
			} catch (Exception e) {
			} finally {
				channelLock.unlock();
			}
		} else {
			channelLock.lock();
			try {
				if (this.isClosed() || this.buffers.size() == 0)
					this.initDB();
				this.buffers.add(channel);
			} finally {
				channelLock.unlock();
			}
		}
	}

	@Override
	public boolean hasOpenChannels() {
		channelLock.lock();
		try {
			if (this.buffers.size() > 0)
				return true;
			else
				return false;
		} catch (Exception e) {
			return false;
		} finally {
			channelLock.unlock();
		}
	}

	public int openChannelsSize() {
		channelLock.lock();
		try {
			return this.buffers.size();
		} catch (Exception e) {
			return -1;
		} finally {
			channelLock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#close()
	 */
	@Override
	public void forceClose() {
		this.syncLock.lock();
		this.initLock.lock();
		this.channelLock.lock();
		this.writeLock.lock();
		try {
			if (!this.closed) {
				SDFSLogger.getLog().debug("Closing [" + mf.getPath() + "]");
				if (Main.safeClose) {
					try {
						ArrayList<DedupFileChannel> al = new ArrayList<DedupFileChannel>();
						for (int i = 0; i < this.buffers.size(); i++) {
							al.add(this.buffers.get(i));
						}
						for (int i = 0; i < al.size(); i++) {
							al.get(i).close(al.get(i).getFlags());
						}
					} catch (Exception e) {
						SDFSLogger.getLog().error(
								"error closing " + mf.getPath(), e);
					}
				} else {
					try {
						this.staticChannel.forceClose();
						this.staticChannel = null;
					} catch (Exception e) {

					}
				}
				try {
					int nwb = this.writeCache();
					SDFSLogger.getLog().debug("Flushed " + nwb + " buffers");

				} catch (Exception e) {
					SDFSLogger.getLog().error(
							"unable to flush " + this.databasePath, e);
				}
				try {
					this.bdb.sync();
				} catch (Exception e) {
				}
				try {
					this.bdb.close();
				} catch (Exception e) {
				}
				this.bdb = null;
				this.closed = true;
				try {
					this.chunkStore.close();
				} catch (Exception e) {
				}
				mf.setDedupFile(this);
				mf.sync();
			}
			SDFSLogger.getLog().debug("Closed [" + mf.getPath() + "]");
		} catch (Exception e) {

			SDFSLogger.getLog().error("error closing " + mf.getPath(), e);
		} finally {
			DedupFileStore.removeOpenDedupFile(this.GUID);
			bdb = null;
			chunkStore = null;
			this.closed = true;
			this.channelLock.unlock();
			this.initLock.unlock();
			this.syncLock.unlock();
			this.writeLock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getGUID()
	 */
	@Override
	public String getGUID() {
		return GUID;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getMetaFile()
	 */
	@Override
	public MetaDataDedupFile getMetaFile() {
		return this.mf;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.annesam.sdfs.io.AbstractDedupFile#removeLock(com.annesam.sdfs.io.
	 * DedupFileLock)
	 */
	@Override
	public void removeLock(DedupFileLock lock) {
		this.removeLock(lock, true);
	}

	@Override
	public void removeLock(DedupFileLock lock, boolean propigate) {
		this.locks.remove(lock);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @seecom.annesam.sdfs.io.AbstractDedupFile#addLock(com.annesam.sdfs.io.
	 * DedupFileChannel, long, long, boolean)
	 */
	@Override
	public DedupFileLock addLock(DedupFileChannel ch, long position, long len,
			boolean shared) throws IOException {
		return this.addLock(ch, position, len, shared, true);
	}

	@Override
	public DedupFileLock addLock(DedupFileChannel ch, long position, long len,
			boolean shared, boolean propigate) throws IOException {
		if (this.overLaps(position, len, shared))
			throw new IOException("Overlapping Lock requested");
		else {
			return new DedupFileLock(ch, position, len, shared);
		}
	}

	public String getDatabasePath() {
		return this.databasePath;
	}

	@Override
	public String getDatabaseDirPath() {
		return this.databaseDirPath;
	}

	private void initDB() throws IOException {
		this.initLock.lock();
		try {
			if (this.isClosed()) {
				File directory = new File(Main.dedupDBStore + File.separator
						+ this.GUID.substring(0, 2) + File.separator
						+ this.GUID);
				File dbf = new File(directory.getPath() + File.separator
						+ this.GUID + ".map");
				File dbc = new File(directory.getPath() + File.separator
						+ this.GUID + ".chk");
				this.databaseDirPath = directory.getPath();
				this.databasePath = dbf.getPath();
				this.chunkStorePath = dbc.getPath();
				if (!directory.exists()) {
					directory.mkdirs();
				}
				this.chunkStore = new LargeLongByteArrayMap(chunkStorePath, -1,
						Main.CHUNK_LENGTH);
				this.bdb = new LongByteArrayMap(this.databasePath);

				this.closed = false;
			}
			DedupFileStore.addOpenDedupFile(this);
		} catch (IOException e) {
			throw e;
		} finally {
			this.initLock.unlock();
		}

	}

	@Override
	public void optimize() throws HashtableFullException {
		DedupFileChannel ch = null;
		try {
			ch = this.getChannel(-1);
			if (this.closed) {
				throw new IOException("file already closed");
			}
			if (!mf.isDedup()) {
				this.writeCache();
				this.checkForDups();
				this.chunkStore.close();
				new File(this.chunkStorePath).delete();
				this.chunkStore = new LargeLongByteArrayMap(chunkStorePath, -1,
						Main.CHUNK_LENGTH);
			} else {
				this.pushLocalDataToChunkStore();
				this.chunkStore.vanish();
				this.chunkStore = null;
				this.chunkStore = new LargeLongByteArrayMap(chunkStorePath, -1,
						Main.CHUNK_LENGTH);
			}
		} catch (IOException e) {

		} catch (FileClosedException e) {

		} finally {
			try {
				this.unRegisterChannel(ch, -1);
			} catch (Exception e) {

			}
			ch = null;
		}
	}

	private void pushLocalDataToChunkStore() throws IOException,
			FileClosedException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		bdb.iterInit();
		Long l = bdb.nextKey();
		SDFSLogger.getLog().debug("removing for dups within " + mf.getPath());
		long doops = 0;
		long records = 0;
		while (l > -1) {
			try {
				SparseDataChunk pck = new SparseDataChunk(bdb.get(l));
				WritableCacheBuffer writeBuffer = null;
				if (pck.isLocalData() && mf.isDedup()) {
					byte[] chunk = chunkStore.get(l);
					byte[] hashloc = HCServiceProxy.writeChunk(pck.getHash(),
							chunk, Main.CHUNK_LENGTH, Main.CHUNK_LENGTH,
							mf.isDedup());
					boolean doop = false;
					if (hashloc[0] == 1)
						doop = true;
					DedupChunk ck = new DedupChunk(pck.getHash(), chunk, l,
							Main.CHUNK_LENGTH, pck.getHashLoc());
					writeBuffer = new WritableCacheBuffer(ck, this);
					writeBuffer.setHashLoc(hashloc);
					this.updateMap(writeBuffer, writeBuffer.getHash(), doop);
					ck = null;
					chunk = null;
					if (doop)
						doops++;
				}
				pck = null;
				writeBuffer = null;
				records++;
				l = bdb.nextKey();
			} catch (Exception e) {
				SDFSLogger.getLog().error(
						"error pushing data for " + mf.getPath(), e);
			}
		}
		if (this.buffers.size() == 0) {
			this.forceClose();
		}
		SDFSLogger.getLog().debug(
				"Checked [" + records + "] blocks found [" + doops
						+ "] new duplicate blocks");
	}

	private void checkForDups() throws IOException, FileClosedException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		bdb.iterInit();
		Long l = bdb.nextKey();
		SDFSLogger.getLog().debug("checking for dups");
		long doops = 0;
		long records = 0;
		long localRec = 0;
		long pos = 0;
		LargeLongByteArrayMap newCS = new LargeLongByteArrayMap(
				this.chunkStorePath + ".new", -1, Main.CHUNK_LENGTH);
		this.chunkStore.lockCollection();
		try {
			while (l > -1) {
				pos = l;
				SparseDataChunk pck = new SparseDataChunk(bdb.get(l));
				if (pck.isLocalData()) {
					byte[] exists = HCServiceProxy.hashExists(pck.getHash(),
							false);
					if (exists[0] != -1) {
						doops++;
						pck.setLocalData(false);
						this.bdb.put(l, pck.getBytes());
					} else {
						newCS.put(l, chunkStore.get(l, false));
					}
					localRec++;
				}
				pck = null;
				records++;
				l = bdb.nextKey();
			}
			newCS.close();
			new File(this.chunkStorePath).delete();
			newCS.move(this.chunkStorePath);
			newCS = null;
			this.chunkStore = new LargeLongByteArrayMap(chunkStorePath, -1,
					Main.CHUNK_LENGTH);
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			this.chunkStore.unlockCollection();
		}
		SDFSLogger.getLog().debug(
				"Checked [" + records + "] blocks found [" + doops
						+ "] new duplicate blocks from [" + localRec
						+ "] local records last key was [" + pos + "]");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#lastModified()
	 */
	@Override
	public long lastModified() throws IOException {
		return mf.lastModified();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getHash(long, boolean)
	 */
	@Override
	public DedupChunk getHash(long location, boolean create)
			throws IOException, FileClosedException {
		if (this.closed) {
			throw new FileClosedException("file already closed");
		}
		long place = this.getChuckPosition(location);
		DedupChunk ck = null;
		try {
			// this.addReadAhead(place);
			byte[] b = this.bdb.get(place);
			if (b != null) {
				SparseDataChunk pck = new SparseDataChunk(b);
				// ByteString data = pck.getData();
				boolean dataEmpty = !pck.isLocalData();
				if (dataEmpty) {
					ck = new DedupChunk(pck.getHash(), place,
							Main.CHUNK_LENGTH, false, pck.getHashLoc());
				} else {
					byte dk[] = chunkStore.get(place);
					ck = new DedupChunk(pck.getHash(), dk, place,
							Main.CHUNK_LENGTH, pck.getHashLoc());
				}
				ck.setDoop(pck.isDoop());
				pck = null;
			}
			b = null;
			if (ck == null && create == true) {
				return createNewChunk(place);
			} else {
				return ck;
			}
		} catch (Exception e) {
			SDFSLogger.getLog().warn(
					"unable to fetch chunk at position " + place, e);

			return null;
		} finally {

		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#removeHash(long)
	 */
	@Override
	public void removeHash(long location) throws IOException {
		this.removeHash(location, true);
	}

	@Override
	public void removeHash(long location, boolean propigate) throws IOException {
		if (this.closed) {
			throw new IOException("file already closed");
		}
		long place = this.getChuckPosition(location);
		try {
			this.writeCache();
			this.bdb.remove(place);
			if (!mf.isDedup())
				this.chunkStore.remove(place);
		} catch (Exception e) {
			SDFSLogger.getLog().warn(
					"unable to remove chunk at position " + place, e);
			throw new IOException(e);
		} finally {
		}
	}

	@Override
	public void truncate(long size) throws IOException {
		this.truncate(size, true);
	}

	@Override
	public void truncate(long size, boolean propigate) throws IOException {
		try {
			if (this.closed) {
				throw new IOException("file already closed");
			}

			this.writeCache();
			if (size == 0) {
				this.mf.getIOMonitor().clearAllCounters(true);
			}
			this.bdb.truncate(size);
			if (!mf.isDedup())
				this.chunkStore.setLength(size);
		} catch (Exception e) {
			SDFSLogger.getLog().warn("unable to truncate to " + size, e);
			throw new IOException(e);
		} finally {
		}

	}

	private DedupChunk createNewChunk(long location) {
		DedupChunk ck = new DedupChunk(new byte[HashFunctionPool.hashLength],
				location, Main.CHUNK_LENGTH, true, new byte[8]);
		return ck;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.sdfs.io.AbstractDedupFile#getChuckPosition(long)
	 */
	@Override
	public long getChuckPosition(long location) {
		long place = location / Main.CHUNK_LENGTH;
		place = place * Main.CHUNK_LENGTH;
		return place;
	}

	@Override
	public boolean isAbsolute() {
		return true;
	}

	@Override
	public void putBufferIntoFlush(DedupChunkInterface writeBuffer) {
		this.flushingBuffers.put(writeBuffer.getFilePosition(), writeBuffer);

	}
}
