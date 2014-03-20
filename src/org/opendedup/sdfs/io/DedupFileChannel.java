package org.opendedup.sdfs.io;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.buse.sdfsdev.BlockDeviceSmallWriteEvent;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.util.StringUtils;
import org.opendedup.sdfs.Main;

import com.google.common.eventbus.EventBus;

/**
 * 
 * @author annesam This is class that is used as an IO interface between the
 *         user based file system, such as Fuse, and the Dedup engine. The dedup
 *         engine is comprised of the SDFS client and the chunk store service.
 *         The DedupFileChannel is loosely based off of the java FileChannel
 *         class.
 */
public class DedupFileChannel {
	// The dedup file associated with this file channel
	private DedupFile df;
	// The MetaDataDedupFile associated with this file channel.
	public MetaDataDedupFile mf;

	private boolean writtenTo = false;
	private long dups;
	private long currentPosition = 0;
	// private String GUID = UUID.randomUUID().toString();
	private ReentrantLock closeLock = new ReentrantLock();
	private boolean closed = false;
	private int flags = -1;
	EventBus eventBus = new EventBus();

	/**
	 * Instantiates the DedupFileChannel
	 * 
	 * @param file
	 *            the MetaDataDedupFile that the filechannel will be opened for
	 * @throws IOException
	 */

	protected DedupFileChannel(MetaDataDedupFile file, int flags)
			throws IOException {
		df = file.getDedupFile();
		mf = file;
		this.flags = flags;
		SparseDedupFile sdf = (SparseDedupFile)df;
		eventBus.register(sdf.bdb);
		SDFSLogger.getLog().debug("Initializing Cache " + mf.getPath());
	}

	public boolean isClosed() {
		this.closeLock.lock();
		try {
			return this.closed;
		} catch (Exception e) {
			return this.closed;
		} finally {
			this.closeLock.unlock();
		}
	}

	/**
	 * Truncate or grow the file
	 * 
	 * @param siz
	 *            the size of the file channel
	 * @exception IOException
	 */
	private ReentrantLock truncateLock = new ReentrantLock();

	public void truncateFile(long siz) throws IOException {
		SDFSLogger.getLog().debug("Truncating File");
		truncateLock.lock();
		try {
			if (siz < mf.length()) {
				df.truncate(siz);
				/*
				 * WritableCacheBuffer writeBuffer = df.getWriteBuffer(siz); int
				 * endPos = (int) (siz - writeBuffer.getFilePosition());
				 * DedupChunk nextDk = df.getHash(writeBuffer.getEndPosition() +
				 * 1, false); while (nextDk != null) {
				 * SDFSLogger.getLog().debug("Removing chunk at position " +
				 * nextDk.getFilePosition());
				 * df.removeHash(nextDk.getFilePosition()); nextDk =
				 * df.getHash(nextDk.getFilePosition() + nextDk.getLength() + 1,
				 * true); } writeBuffer.truncate(endPos); //
				 * df.writeCache(writeBuffer,true);
				 */

			}
			mf.setLastAccessed(System.currentTimeMillis());
			mf.setLength(siz, true);
		} finally {
			truncateLock.unlock();
		}
	}

	/**
	 * 
	 * @return the number of duplicates found while this file channel is open.
	 */
	public long getDups() {
		return dups;
	}

	/**
	 * 
	 * @return the current position the file is reading or writing from
	 */
	public long position() {
		return this.currentPosition;
	}

	/**
	 * 
	 * @param pos
	 *            sets the current position of the file
	 */
	public void position(long pos) {
		this.currentPosition = pos;
	}

	/**
	 * 
	 * @return the current size of the file
	 */
	public long size() {
		return this.mf.length();
	}

	/**
	 * 
	 * @return the path of the file
	 */
	public String getPath() {
		return this.mf.getPath();
	}

	/**
	 * 
	 * @return the path of the file
	 */
	public String getName() {
		return this.mf.getPath();
	}

	/**
	 * 
	 * @return the MetaDataDedupFile associated with this DedupFileChannel
	 */
	public MetaDataDedupFile getFile() {
		return this.mf;
	}

	/**
	 * Forces data to be synced to disk
	 * 
	 * @param metaData
	 *            true will sync data
	 * @throws IOException
	 * @throws FileClosedException
	 */
	public void force(boolean metaData) throws IOException, FileClosedException {
		// FixMe Does not persist chunks. This may be an issue.
		try {
			df.sync(false);
		} catch (FileClosedException e) {
			SDFSLogger.getLog().warn(
					mf.getPath() + " is closed but still writing");
			this.closeLock.lock();
			try {
				df.registerChannel(this);
				this.closed = false;
				this.force(metaData);
			} finally {
				this.closeLock.unlock();
			}
		}
		if(mf.getDev() != null)
			mf.sync();
	}

	/**
	 * 
	 * @param lastModified
	 *            sets the last time the data was modified for the underlying
	 *            file
	 * @throws IOException
	 */
	public void setLastModified(long lastModified) throws IOException {
		mf.setLastModified(lastModified);
	}

	/**
	 * writes data to the DedupFile
	 * 
	 * @param bbuf
	 *            the bytes to write
	 * @param len
	 *            the length of data to write
	 * @param pos
	 *            the position within the file to write the data to
	 * @param offset
	 *            the offset within the bbuf to start the write from
	 * @throws java.io.IOException
	 */
	public void writeFile(ByteBuffer buf, int len, int pos, long offset,boolean propigate)
			throws java.io.IOException {
		// this.addAio();
		try {
			buf.position(pos);
			this.writtenTo = true;
			long _cp = offset;
			// ByteBuffer buf = ByteBuffer.wrap(bbuf, pos, len);
			int bytesLeft = len;
			int write = 0;
			while (bytesLeft > 0) {
				// Check to see if we need a new Write buffer
				// WritableCacheBuffer writeBuffer = df.getWriteBuffer(_cp);
				// Find out where to write to in the buffer
				long filePos = df.getChuckPosition(_cp);
				int startPos = (int) (_cp - filePos);
				if (startPos < 0)
					SDFSLogger.getLog().fatal("Error " + _cp + " " + filePos);
				// Find out how many total bytes there are left to write in
				// this
				// loop
				int endPos = startPos + bytesLeft;
				// If the writebuffer can fit what is left, write it and
				// quit.
				if ((endPos) <= Main.CHUNK_LENGTH) {
					
					/*
					 * if (endPos == Main.CHUNK_LENGTH) newBuf = true;
					 */
					DedupChunkInterface writeBuffer = null;
					byte[] b = new byte[bytesLeft];
					try {
						buf.get(b);
					} catch (java.nio.BufferUnderflowException e) {
						buf.get(b, 0, buf.capacity() - buf.position());
						SDFSLogger.getLog().info(
								"ss buffer underflow writing "
										+ (buf.capacity() - buf.position())
										+ " instead of " + bytesLeft);
					}
					if(endPos != Main.CHUNK_LENGTH && propigate && mf.getDev() != null) {
						eventBus.post(new BlockDeviceSmallWriteEvent(mf.getDev(),ByteBuffer.wrap(b),filePos+startPos,bytesLeft));
					}
					while (writeBuffer == null) {
						try {
							writeBuffer = df.getWriteBuffer(filePos);
							writeBuffer.write(b, startPos);
							if(Main.volume.isClustered())
								writeBuffer.flush();
						} catch (BufferClosedException e) {
							writeBuffer = null;
							SDFSLogger.getLog().debug("trying to write again");
						}
					}
					write = write + bytesLeft;
					_cp = _cp + bytesLeft;
					bytesLeft = 0;
				} else {
					int _len = Main.CHUNK_LENGTH - startPos;
					/*
					 * if (_len == Main.CHUNK_LENGTH) newBuf = true;
					 */
					DedupChunkInterface writeBuffer = null;
					byte[] b = new byte[_len];
					try {
						buf.get(b);
					} catch (java.nio.BufferUnderflowException e) {
						buf.get(b, 0, buf.capacity() - buf.position());
						SDFSLogger.getLog().info(
								"buffer underflow getting "
										+ (buf.capacity() - buf.position())
										+ " instead of " + _len);
					}
					while (writeBuffer == null) {
						try {
							writeBuffer = df.getWriteBuffer(filePos);
							writeBuffer.write(b, startPos);
							if(startPos != 0 &&propigate && mf.getDev() != null) {
								eventBus.post(new BlockDeviceSmallWriteEvent(mf.getDev(),ByteBuffer.wrap(b),filePos+startPos,_len));
							}
							if(Main.volume.isClustered())
								writeBuffer.flush();
						} catch (BufferClosedException e) {
							SDFSLogger.getLog().debug("trying to write again");
							writeBuffer = null;
						}
					}
					_cp = _cp + _len;
					bytesLeft = bytesLeft - _len;
					write = write + _len;
				}
				this.currentPosition = _cp;
				if (_cp > mf.length()) {
					mf.setLength(_cp, false);
				}
				mf.setLastModified(System.currentTimeMillis());
			}
		} catch (FileClosedException e) {
			SDFSLogger.getLog().warn(
					mf.getPath() + " is closed but still writing");
			this.closeLock.lock();
			try {
				df.registerChannel(this);
				this.closed = false;
				this.writeFile(buf, len, pos, offset,propigate);
			} finally {
				this.closeLock.unlock();
			}
		} catch (IOException e) {
			SDFSLogger.getLog().fatal(
					"error while writing to " + this.mf.getPath() + " "
							+ e.toString(), e);
			Main.volume.addWriteError();
			throw new IOException("error while writing to " + this.mf.getPath()
					+ " " + e.toString());
		} finally {
			// this.removeAio();
		}
	}

	/**
	 * Closes the byte array
	 * 
	 * @throws IOException
	 */
	protected void close(int flags) throws IOException {
		if (Main.safeClose) {
			if (!this.isClosed()) {
				this.closeLock.lock();
				try {
					if (this.writtenTo && Main.safeSync) {
						df.writeCache();
						mf.sync();
						df.sync(false);

					}
				} catch (Exception e) {

				} finally {
					this.closed = true;
					this.closeLock.unlock();
				}
			}
		}
	}

	/**
	 * Closes the byte array
	 * 
	 * @throws IOException
	 */
	protected void forceClose() throws IOException {
		if (!this.isClosed()) {
			this.closeLock.lock();
			try {
				if (this.writtenTo && Main.safeSync) {
					df.writeCache();
					mf.sync();
					df.sync(false);

				}
			} catch (Exception e) {

			} finally {
				df.unRegisterChannel(this, this.getFlags());
				this.closed = true;
				this.closeLock.unlock();
			}
		}
	}

	/**
	 * Reads data from the DedupFile
	 * 
	 * @param bbuf
	 *            the byte array to copy the data to.
	 * @param bufPos
	 *            the position within the array to copy the data too.
	 * @param siz
	 *            the mount of data to copy to the bbuf.
	 * @param filePos
	 *            the position within the file to read the data from
	 * @return the bytes read
	 * @throws IOException
	 */
	public int read(ByteBuffer buf, int bufPos, int siz, long filePos)
			throws IOException {
		// this.addAio();
		// SDFSLogger.getLog().debug("reading at " + filePos + " "
		// +buf.capacity() + " bytes");
		try {
			if (filePos >= mf.length() && !Main.blockDev) {
				return -1;
			}
			long currentLocation = filePos;
			buf.position(bufPos);
			int bytesLeft = siz;
			long futureFilePostion = bytesLeft + currentLocation;
			if(Main.blockDev && futureFilePostion > mf.length())
				mf.setLength(futureFilePostion, false);
			if (futureFilePostion > mf.length()) {
				bytesLeft = (int) (mf.length() - currentLocation);
			}
			int read = 0;
			while (bytesLeft > 0) {
				DedupChunkInterface readBuffer = null;
				int startPos = 0;
				byte[] _rb = null;
				try {
					
					readBuffer = df.getReadBuffer(currentLocation);
					_rb = readBuffer.getReadChunk();
					startPos = (int) (currentLocation - readBuffer
							.getFilePosition());
					int endPos = startPos + bytesLeft;
					if ((endPos) <= readBuffer.getLength()) {
						
						buf.put(_rb, startPos, bytesLeft);
						mf.getIOMonitor().addBytesRead(bytesLeft, true);
						read = read + bytesLeft;
						bytesLeft = 0;
					} else {
						int _len = readBuffer.getLength() - startPos;

						buf.put(_rb, startPos, _len);
						mf.getIOMonitor().addBytesRead(_len, true);
						currentLocation = currentLocation + _len;
						bytesLeft = bytesLeft - _len;
						read = read + _len;
					}
					if(Main.volume.isClustered())
						readBuffer.flush();
				} catch (FileClosedException e) {
					SDFSLogger.getLog().warn(
							mf.getPath() + " is closed but still writing");
					this.closeLock.lock();
					try {
						df.registerChannel(this);
						this.closed = false;
						this.read(buf, bufPos, siz, filePos);
					} finally {
						this.closeLock.unlock();
					}
				} catch (Exception e) {
					SDFSLogger.getLog().fatal("Error while reading buffer ", e);
					SDFSLogger.getLog().fatal(
							"Error Reading Buffer " + StringUtils.getHexString(readBuffer.getHash())
									+ " start position [" + startPos
									+ "]  bytes left [" + bytesLeft
									+ "] file Postion [" + currentLocation
									+ "] buf size [" + buf.capacity() + "] read buffer len [" + _rb.length + "]");
					throw new IOException("Error reading buffer");
				}
				if (currentLocation == mf.length()) {
					return read;
				}
				mf.setLastAccessed(System.currentTimeMillis());
				this.currentPosition = currentLocation;
			}
			return read;
		} catch (Exception e) {
			SDFSLogger.getLog().error("unable to read " + mf.getPath(), e);
			Main.volume.addReadError();
			throw new IOException(e);
		} finally {
			// this.removeAio();
		}

	}

	public DedupFile getDedupFile() {
		return this.df;
	}

	/**
	 * Seek to the specified file position.
	 * 
	 * @param pos
	 *            long
	 * @param typ
	 *            int
	 * @return long
	 * @exception IOException
	 */
	public long seekFile(long pos, int typ) throws IOException {

		// Check if the current file position is the required file position

		switch (typ) {

		// From start of file

		case SeekType.StartOfFile:
			if (this.position() != pos)
				this.currentPosition = pos;
			break;

		// From current position

		case SeekType.CurrentPos:
			this.currentPosition = this.currentPosition + pos;
			break;
		// From end of file
		case SeekType.EndOfFile: {
			this.currentPosition = pos;
		}
			break;
		}
		mf.setLastAccessed(System.currentTimeMillis());
		// Return the new file position
		return this.position();
	}

	/**
	 * 
	 * @return the MetaDataDedupFile for this DedupFileChannel
	 * @throws IOException
	 */
	public MetaDataDedupFile openFile() throws IOException {
		return this.mf;
	}

	/**
	 * Tries to lock a file at a specific position
	 * 
	 * @param position
	 *            the position to lock the file at.
	 * @param size
	 *            the size of the data to be locked
	 * @param shared
	 *            if the lock is shared or not
	 * @return true if it is locked
	 * @throws IOException
	 */
	public DedupFileLock tryLock(long position, long size, boolean shared)
			throws IOException {
		return df.addLock(this, position, size, shared);
	}

	/**
	 * Tries to lock a file exclusively
	 * 
	 * @return true if the file is locked
	 * @throws IOException
	 */
	public DedupFileLock tryLock() throws IOException {
		return df.addLock(this, 0, mf.length(), false);
	}

	/**
	 * Removes an existing lock on a file
	 * 
	 * @param lock
	 *            the lock on the file
	 */
	public void removeLock(DedupFileLock lock) {
		lock.release();
		df.removeLock(lock);
	}

	public int getFlags() {
		return flags;
	}

	public void setFlags(int flags) {
		this.flags = flags;
	}
	
	public void trim(long start,int len) throws IOException {
		df.trim(start, len);
	}
}
