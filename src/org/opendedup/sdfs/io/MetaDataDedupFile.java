package org.opendedup.sdfs.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.ParserConfigurationException;

import org.opendedup.collections.HashtableFullException;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.filestore.DedupFileStore;
import org.opendedup.sdfs.filestore.MetaFileStore;
import org.opendedup.sdfs.io.events.MFileDeleted;
import org.opendedup.sdfs.io.events.MFileWritten;
import org.opendedup.sdfs.monitor.IOMonitor;
import org.opendedup.sdfs.notification.SDFSEvent;
import org.opendedup.util.ByteUtils;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.common.eventbus.EventBus;

/**
 * 
 * @author annesam Stores Meta-Data about a dedupFile. This class is modeled
 *         from the java.io.File class. Meta-Data files are stored within the
 *         MetaDataFileStore @see com.annesam.filestore.MetaDataFileStore
 */
public class MetaDataDedupFile implements java.io.Externalizable {

	private static final long serialVersionUID = -4598940197202968523L;
	private static EventBus eventBus = new EventBus();
	transient public static final String pathSeparator = File.pathSeparator;
	transient public static final String separator = File.separator;
	transient public static final char pathSeparatorChar = File.pathSeparatorChar;
	transient public static final char separatorChar = File.separatorChar;
	private long length = 0;
	private String path = "";
	private String backingFile = null;
	private boolean iterWeaveCP = false;
	private long lastModified = 0;
	private long lastAccessed = 0;
	private boolean execute = true;
	private boolean read = true;
	private boolean write = true;
	private boolean directory = false;
	private boolean hidden = false;
	private boolean ownerWriteOnly = false;
	private boolean ownerExecOnly = false;
	private boolean ownerReadOnly = false;
	private String dfGuid = null;
	private String guid = "";
	private IOMonitor monitor;
	private boolean vmdk;
	private int permissions;
	private int owner_id = -1;
	private int group_id = -1;
	private int mode = -1;
	private HashMap<String, String> extendedAttrs = new HashMap<String, String>();
	private boolean dedup = Main.dedupFiles;
	private boolean symlink = false;
	private String symlinkPath = null;
	private String version = Main.version;
	private BlockDev blkdev = null;
	private boolean dirty = false;
	private long attributes = 0;

	public static void registerListener(Object obj) {
		eventBus.register(obj);
	}

	public BlockDev getDev() {
		return this.blkdev;
	}

	public void setDev(BlockDev dev) {
		this.blkdev = dev;
	}

	/**
	 * 
	 * @return true if all chunks within the file will be deduped.
	 */
	public boolean isDedup() {
		return dedup;
	}

	public int getMode() throws IOException {
		if (mode == -1) {
			Path p = Paths.get(this.path);
			this.mode = (Integer) Files.getAttribute(p, "unix:mode");
		}
		return this.mode;
	}

	public void setMode(int mode) throws IOException {
		setMode(mode, true);
	}

	public void setMode(int mode, boolean propigateEvent) throws IOException {
		this.mode = mode;
		this.dirty = true;
		Path p = Paths.get(this.path);
		Files.setAttribute(p, "unix:mode", Integer.valueOf(mode),
				LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * 
	 * @param dedup
	 *            if true all chunks will be deduped, Otherwise chunks will be
	 *            deduped opportunistically.
	 * @throws IOException
	 */
	public void setDedup(boolean dedupNow) throws IOException,
			HashtableFullException {
		setDedup(dedupNow, true);
	}

	/**
	 * 
	 * @param propigateEvent
	 *            TODO
	 * @param dedup
	 *            if true all chunks will be deduped, Otherwise chunks will be
	 *            deduped opportunistically.
	 * @throws IOException
	 */
	public void setDedup(boolean dedupNow, boolean propigateEvent)
			throws IOException, HashtableFullException {
		if (!this.dedup && dedupNow) {
			try {
				this.dedup = dedupNow;
				this.getDedupFile().optimize();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				SDFSLogger.getLog().error(
						"unable to set dedup on " + this.getPath(), e);
			}
		}
		this.dedup = dedupNow;
	}

	/**
	 * adds a posix extended attribute
	 * 
	 * @param name
	 *            the name of the attribute
	 * @param value
	 *            the value of the attribute
	 */
	public void addXAttribute(String name, String value) {
		addXAttribute(name, value, true);
	}

	/**
	 * adds a posix extended attribute
	 * 
	 * @param name
	 *            the name of the attribute
	 * @param value
	 *            the value of the attribute
	 * @param propigateEvent
	 *            TODO
	 */
	public void addXAttribute(String name, String value, boolean propigateEvent) {
		extendedAttrs.put(name, value);
	}

	public void setBackingFile(String file) {
		this.backingFile = file;
	}

	public String getBackingFile() {
		return this.backingFile;
	}

	public void setInterWeaveCP(boolean interweave) {
		this.iterWeaveCP = interweave;
	}

	public boolean isInterWeaveCP() {
		return this.iterWeaveCP;
	}

	/**
	 * returns an extended attribute for a give name
	 * 
	 * @param name
	 * @return the extended attribute
	 */
	public String getXAttribute(String name) {
		if (this.extendedAttrs.containsKey(name))
			return extendedAttrs.get(name);
		else
			return null;
	}

	/**
	 * 
	 * @return list of all extended attribute names
	 */
	public String[] getXAttersNames() {
		String[] keys = new String[this.extendedAttrs.size()];
		Iterator<String> iter = this.extendedAttrs.keySet().iterator();
		int i = 0;
		while (iter.hasNext()) {
			keys[i] = iter.next();
			i++;
		}
		return keys;
	}

	/**
	 * 
	 * @return posix permissions e.g. 0777
	 */
	public int getPermissions() {
		return permissions;
	}

	/**
	 * 
	 * @param permissions
	 *            sets permissions
	 */
	public void setPermissions(int permissions) {
		setPermissions(permissions, true);
	}

	/**
	 * 
	 * @param permissions
	 *            sets permissions
	 * @param propigateEvent
	 *            TODO
	 */
	public void setPermissions(int permissions, boolean propigateEvent) {
		this.permissions = permissions;
	}

	/**
	 * 
	 * @return the file owner id
	 * @throws IOException
	 */
	public int getOwner_id() throws IOException {
		if (owner_id == -1) {
			Path p = Paths.get(this.path);
			this.owner_id = (Integer) Files.getAttribute(p, "unix:uid");
		}
		return owner_id;
	}

	/**
	 * 
	 * @param owner_id
	 *            sets the file owner id
	 * @throws IOException
	 */
	public void setOwner_id(int owner_id) throws IOException {
		setOwner_id(owner_id, true);
	}

	/**
	 * 
	 * @param owner_id
	 *            sets the file owner id
	 * @param propigateEvent
	 *            TODO
	 * @throws IOException
	 */
	public void setOwner_id(int owner_id, boolean propigateEvent)
			throws IOException {
		this.owner_id = owner_id;
		Path p = Paths.get(this.path);
		Files.setAttribute(p, "unix:uid", Integer.valueOf(owner_id),
				LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * 
	 * @return returns the group owner id
	 * @throws IOException
	 */
	public int getGroup_id() throws IOException {
		if (group_id == -1) {
			Path p = Paths.get(this.path);
			this.group_id = (Integer) Files.getAttribute(p, "unix:gid");
		}
		return group_id;
	}

	/**
	 * 
	 * @param group_id
	 *            sets the group owner id
	 * @throws IOException
	 */
	public void setGroup_id(int group_id) throws IOException {
		setGroup_id(group_id, true);
	}

	/**
	 * 
	 * @param group_id
	 *            sets the group owner id
	 * @param propigateEvent
	 *            TODO
	 * @throws IOException
	 */
	public void setGroup_id(int group_id, boolean propigateEvent)
			throws IOException {
		this.group_id = group_id;
		Path p = Paths.get(this.path);
		Files.setAttribute(p, "unix:gid", Integer.valueOf(group_id),
				LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * 
	 * @return true if this file is a vmdk
	 */
	public boolean isVmdk() {
		return vmdk;
	}

	/**
	 * 
	 * @param vmdk
	 *            flags this file as a vmdk if true
	 */
	public void setVmdk(boolean vmdk) {
		setVmdk(vmdk, true);
	}

	/**
	 * 
	 * @param vmdk
	 *            flags this file as a vmdk if true
	 * @param propigateEvent
	 *            TODO
	 */
	public void setVmdk(boolean vmdk, boolean propigateEvent) {
		this.vmdk = vmdk;
	}

	public static MetaDataDedupFile getFile(String path) {

		File f = new File(path);
		MetaDataDedupFile mf = null;
		Path p = Paths.get(path);
		if (Files.isSymbolicLink(p)) {
			mf = new MetaDataDedupFile();
			mf.path = path;
			mf.symlink = true;
			try {
				mf.symlinkPath = Files.readSymbolicLink(p).toFile().getPath();
				if (new File(mf.symlinkPath).isDirectory())
					mf.directory = true;
			} catch (IOException e) {
				SDFSLogger.getLog().warn(e);
			}
		} else if (!f.exists() || f.isDirectory()) {
			mf = new MetaDataDedupFile(path);
		} else {
			ObjectInputStream in = null;
			try {
				in = new ObjectInputStream(new FileInputStream(path));
				mf = (MetaDataDedupFile) in.readObject();
				mf.path = path;
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(
							"reading in file " + mf.path + " df=" + mf.dfGuid);
			} catch (Exception e) {
				SDFSLogger.getLog().fatal("unable to de-serialize " + path, e);
				mf = new MetaDataDedupFile(path);
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (IOException e) {
					}
				}
			}

		}
		return mf;
	}

	/**
	 * 
	 * @return returns the IOMonitor for this file. IOMonitors monitor
	 *         reads,writes, and dedup rate.
	 */
	public IOMonitor getIOMonitor() {
		if (monitor == null)
			monitor = new IOMonitor(this);
		return monitor;
	}

	/**
	 * 
	 * @param path
	 *            the path to the dedup file.
	 */
	private MetaDataDedupFile(String path) {

		init(path);
	}

	/**
	 * 
	 * @param path
	 *            the path to the dedup file.
	 */
	public MetaDataDedupFile() {
	}

	/**
	 * 
	 * @param parent
	 *            the parent folder
	 * @param child
	 *            the file name
	 */
	public MetaDataDedupFile(File parent, String child) {

		String pth = parent.getAbsolutePath() + File.separator + child;
		init(pth);
	}

	public DedupFile getDedupFile() throws IOException {
		this.writeLock.lock();
		try {
			if (this.dfGuid == null) {
				DedupFile df = new SparseDedupFile(this);
				this.dfGuid = df.getGUID();
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(
							"No DF EXISTS .... Set dedup file for "
									+ this.getPath() + " to " + this.dfGuid);
				this.sync();
				return df;
			} else {
				return DedupFileStore.getDedupFile(this);
			}
		} finally {
			writeLock.unlock();
		}
	}

	public void setDfGuid(String guid) {
		setDfGuid(guid, true);
	}

	public void setDfGuid(String guid, boolean propigateEvent) {
		this.dfGuid = guid;
	}

	/**
	 * 
	 * @return the guid associated with this file
	 */
	public String getGUID() {
		return guid;
	}

	/**
	 * Clones a file and the underlying DedupFile
	 * 
	 * @param snaptoPath
	 *            the path to clone to
	 * @param overwrite
	 *            if true, it will overwrite the destination file if it alreay
	 *            exists
	 * @return the new clone
	 * @throws IOException
	 */
	public MetaDataDedupFile snapshot(String snaptoPath, boolean overwrite,
			SDFSEvent evt) throws IOException {
		return snapshot(snaptoPath, overwrite, evt, true);
	}

	/**
	 * Clones a file and the underlying DedupFile
	 * 
	 * @param snaptoPath
	 *            the path to clone to
	 * @param overwrite
	 *            if true, it will overwrite the destination file if it alreay
	 *            exists
	 * @param propigateEvent
	 *            TODO
	 * @return the new clone
	 * @throws IOException
	 */
	public MetaDataDedupFile snapshot(String snaptoPath, boolean overwrite,
			SDFSEvent evt, boolean propigateEvent) throws IOException {
		if (this.isSymlink()) {

			File dst = new File(snaptoPath);
			File src = new File(this.getPath());
			if (dst.exists() && !overwrite) {
				throw new IOException(snaptoPath + " already exists");
			}
			Path srcP = Paths.get(src.getPath());
			Path dstP = Paths.get(dst.getPath());
			try {
				Files.createSymbolicLink(dstP, Files.readSymbolicLink(srcP)
						.toFile().toPath());
			} catch (IOException e) {
				SDFSLogger.getLog().error(
						"error symlinking " + this.getPath() + " to "
								+ snaptoPath, e);
			}
			return getFile(snaptoPath);
		} else if (!this.isDirectory()) {
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug("is snapshot file");
			File f = new File(snaptoPath);
			if (f.exists() && !overwrite)
				throw new IOException("path exists [" + snaptoPath
						+ "]Cannot overwrite existing data ");
			else if (f.exists()) {
				MetaFileStore.removeMetaFile(snaptoPath, true);
			}

			if (!f.getParentFile().exists())
				f.getParentFile().mkdirs();
			MetaDataDedupFile _mf = new MetaDataDedupFile(snaptoPath);
			_mf.directory = this.directory;
			_mf.execute = this.execute;
			_mf.hidden = this.hidden;
			_mf.lastModified = this.lastModified;
			_mf.setLength(this.length, false, true);
			_mf.ownerExecOnly = this.ownerExecOnly;
			_mf.ownerReadOnly = this.ownerReadOnly;
			_mf.ownerWriteOnly = this.ownerWriteOnly;
			_mf.read = this.read;
			_mf.write = this.write;
			_mf.owner_id = this.owner_id;
			_mf.group_id = this.group_id;
			_mf.permissions = this.permissions;
			_mf.dedup = this.dedup;
			_mf.attributes = this.attributes;
			this.getGUID();
			if (!this.dedup) {
				try {
					this.setDedup(true, true);
					try {
						_mf.dfGuid = DedupFileStore.cloneDedupFile(this, _mf)
								.getGUID();
					} catch (java.lang.NullPointerException e) {
						if (SDFSLogger.isDebug())
							SDFSLogger.getLog().debug(
									"no dedupfile for " + this.path, e);
					}
				} catch (HashtableFullException e) {
					throw new IOException(e);
				} finally {
					try {
						this.setDedup(false, true);
					} catch (HashtableFullException e) {
						SDFSLogger
								.getLog()
								.error("error while setting dedup option back to false",
										e);
					}
				}
			} else {
				try {
					DedupFileStore.cloneDedupFile(this, _mf);
				} catch (java.lang.NullPointerException e) {
					if (SDFSLogger.isDebug())
						SDFSLogger.getLog().debug(
								"no dedupfile for " + this.path, e);
				}
			}
			_mf.getIOMonitor().setVirtualBytesWritten(
					this.getIOMonitor().getVirtualBytesWritten(), true);
			_mf.getIOMonitor()
					.setDuplicateBlocks(
							this.getIOMonitor().getDuplicateBlocks()
									+ this.getIOMonitor()
											.getActualBytesWritten(), true);
			Main.volume.addDuplicateBytes(this.getIOMonitor()
					.getDuplicateBlocks()
					+ this.getIOMonitor().getActualBytesWritten(), true);
			Main.volume.addVirtualBytesWritten(this.getIOMonitor()
					.getVirtualBytesWritten(), true);
			_mf.setVmdk(this.isVmdk(), true);
			_mf.dirty = true;
			_mf.unmarshal();
			evt.curCt = evt.curCt + 1;
			return _mf;
		} else {
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug("is snapshot dir");
			File f = new File(snaptoPath);
			f.mkdirs();
			int trimlen = this.getPath().length();
			MetaDataDedupFile[] files = this.listFiles();
			for (int i = 0; i < files.length; i++) {

				if (!files[i].getPath().equals(f.getPath())) {

					MetaDataDedupFile file = files[i];
					String newPath = snaptoPath + File.separator
							+ file.getPath().substring(trimlen);
					file.snapshot(newPath, overwrite, evt, propigateEvent);
				}
			}
			try {
				return MetaFileStore.getMF(snaptoPath);
			} catch (Exception e) {
				SDFSLogger.getLog().error(
						"unable to traverse " + this.getPath());
				throw new IOException(e);
			}
		}
	}

	/**
	 * Clones a file and the underlying DedupFile
	 * 
	 * @param snaptoPath
	 *            the path to clone to
	 * @param overwrite
	 *            if true, it will overwrite the destination file if it alreay
	 *            exists
	 * @return the new clone
	 * @throws IOException
	 */
	public void copyTo(String npath, boolean overwrite) throws IOException {
		copyTo(npath, overwrite, true);
	}

	/**
	 * Clones a file and the underlying DedupFile
	 * 
	 * @param overwrite
	 *            if true, it will overwrite the destination file if it alreay
	 *            exists
	 * @param propigateEvent
	 *            TODO
	 * @param snaptoPath
	 *            the path to clone to
	 * 
	 * @return the new clone
	 * @throws IOException
	 */
	public void copyTo(String npath, boolean overwrite, boolean propigateEvent)
			throws IOException {
		String snaptoPath = new File(npath + File.separator + "files")
				.getPath() + File.separator;
		File f = new File(snaptoPath);
		if (f.exists() && !overwrite)
			throw new IOException("while copying path exists [" + snaptoPath
					+ "]Cannot overwrite existing data ");
		if (!f.exists())
			f.mkdirs();

		String cpCmd = "cp -rf --preserve=all " + this.path + " " + snaptoPath;
		Process p = Runtime.getRuntime().exec(cpCmd);

		try {
			int ecode = p.waitFor();
			if (ecode != 0)
				throw new IOException("unable to copy " + this.path
						+ " cp exit code is " + ecode);

		} catch (Exception e) {
			throw new IOException(e);
		}
		MetaDataDedupFile dmf = MetaDataDedupFile.getFile(snaptoPath);
		dmf.copyDir(npath);
	}

	private void copyDir(String npath) throws IOException {
		String[] files = this.list();

		for (int i = 0; i < files.length; i++) {
			Path p = Paths.get(this.getPath() + File.separator + files[i]);
			if (Files.isSymbolicLink(p)) {

			} else {
				MetaDataDedupFile file = MetaDataDedupFile.getFile(this
						.getPath() + File.separator + files[i]);
				if (file.isDirectory())
					file.copyDir(npath);
				else {
					if (SDFSLogger.isDebug())
						SDFSLogger.getLog().debug(
								"copy dedup file for : " + file.getPath()
										+ " guid :" + file.getDfGuid());
					if (file.dfGuid != null) {
						if (DedupFileStore.fileOpen(file))
							file.getDedupFile().copyTo(npath, true);
						else {
							File sdbdirectory = new File(Main.dedupDBStore
									+ File.separator
									+ file.dfGuid.substring(0, 2)
									+ File.separator + file.dfGuid);
							if (sdbdirectory.exists()) {
								Path sdbf = new File(sdbdirectory.getPath()
										+ File.separator + file.dfGuid + ".map")
										.toPath();

								File ddbdir = new File(npath + File.separator
										+ "ddb" + File.separator
										+ file.dfGuid.substring(0, 2)
										+ File.separator + file.dfGuid);
								ddbdir.mkdirs();
								Path ddbf = new File(ddbdir.getPath()
										+ File.separator + file.dfGuid + ".map")
										.toPath();

								Files.copy(sdbf, ddbf,
										StandardCopyOption.REPLACE_EXISTING,
										StandardCopyOption.COPY_ATTRIBUTES);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * initiates the MetaDataDedupFile
	 * 
	 * @param path
	 *            the path to the file
	 */
	private void init(String path) {
		this.lastAccessed = System.currentTimeMillis();
		this.path = path;
		File f = new File(path);
		if (!f.exists()) {
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug(
						"Creating new MetaFile for " + this.path);
			this.guid = UUID.randomUUID().toString();
			monitor = new IOMonitor(this);
			this.owner_id = Main.defaultOwner;
			this.group_id = Main.defaultGroup;
			this.permissions = Main.defaultFilePermissions;
			this.lastModified = System.currentTimeMillis();
			this.dedup = Main.dedupFiles;
			this.setLength(0, false, true);
			this.dirty = true;
			this.sync();
		} else if (f.isDirectory()) {
			this.permissions = Main.defaultDirPermissions;
			this.owner_id = Main.defaultOwner;
			this.group_id = Main.defaultGroup;
			this.directory = true;
			this.length = 4096;
		}

	}

	private ReentrantLock writeLock = new ReentrantLock();

	/**
	 * Writes the stub for this file to disk. Stubs are pointers written to a
	 * file system that map to virtual filesystem directory and file structure.
	 * The stub only contains the guid associated with the file in question.
	 * 
	 * @return true if written
	 */
	private boolean writeFile() {
		writeLock.lock();
		ObjectOutputStream out = null;
		try {
			File f = new File(this.path);
			if (!f.isDirectory()) {

				try {

					if (f.getParentFile() == null
							|| !f.getParentFile().exists())
						f.getParentFile().mkdirs();
					out = new ObjectOutputStream(
							new FileOutputStream(this.path));
					out.writeObject(this);
					out.flush();
					out.close();
					eventBus.post(new MFileWritten(this));
					this.dirty = false;
				} catch (Exception e) {
					SDFSLogger.getLog().warn(
							"unable to write file metadata for [" + this.path
									+ "]", e);
					return false;
				}
			}
		} finally {
			writeLock.unlock();
		}
		return true;
	}

	/**
	 * Serializes the file to the MetaFileStore
	 * 
	 * @return true if serialized
	 */
	private boolean unmarshal() {
		return this.writeFile();
	}

	/**
	 * 
	 * @return returns the GUID for the underlying DedupFile
	 */
	public String getDfGuid() {
		return dfGuid;
	}

	/**
	 * 
	 * @return time when file was last modified
	 */
	/**
	 * 
	 * @return time when file was last modified
	 */
	public long lastModified() {
		if (this.dfGuid != null)
			return lastModified;
		if (this.isDirectory())
			return new File(this.path).lastModified();
		return lastModified;
	}

	/**
	 * 
	 * @return creates a blank new file
	 */
	public boolean createNewFile() {
		return this.unmarshal();
	}

	/**
	 * 
	 * @return true if hidden
	 */
	public boolean isHidden() {
		return hidden;
	}

	/**
	 * 
	 * @param hidden
	 *            true if hidden
	 */
	public void setHidden(boolean hidden) {
		setHidden(hidden, true);
	}

	/**
	 * 
	 * @param hidden
	 *            true if hidden
	 * @param propigateEvent
	 *            TODO
	 */
	public void setHidden(boolean hidden, boolean propigateEvent) {
		this.hidden = hidden;
		this.dirty = true;
		this.unmarshal();
	}

	/**
	 * 
	 * @return true if deleted
	 */
	public boolean deleteStub() {
		this.writeLock.lock();
		try {
			File f = new File(this.path);
			boolean del = f.delete();
			if (del)
				eventBus.post(new MFileDeleted(this));
			return del;
		} finally {
			this.writeLock.unlock();
		}
	}

	/**
	 * 
	 * @param df
	 *            the DedupFile that will be referenced within this file
	 */
	protected void setDedupFile(DedupFile df) {
		setDedupFile(df, true);
	}

	/**
	 * 
	 * @param df
	 *            the DedupFile that will be referenced within this file
	 * @param propigateEvent
	 *            TODO
	 */
	protected void setDedupFile(DedupFile df, boolean propigateEvent) {
		if (!df.getGUID().equalsIgnoreCase(this.dfGuid)) {
			this.dirty = true;
			this.dfGuid = df.getGUID();
		}
	}

	/**
	 * 
	 * @return the children of this directory and null if it is not a directory.
	 */
	public String[] list() {
		File f = new File(this.path);
		if (f.isDirectory()) {
			return f.list();
		} else {
			return null;
		}
	}

	/**
	 * 
	 * @return the children as MetaDataDedupFiles or null if it is not a
	 *         directory
	 */
	public MetaDataDedupFile[] listFiles() {
		File f = new File(this.path);
		if (f.isDirectory()) {
			String[] files = f.list();
			MetaDataDedupFile[] df = new MetaDataDedupFile[files.length];
			for (int i = 0; i < df.length; i++) {

				try {
					df[i] = MetaFileStore.getMF(this.getPath() + File.separator
							+ files[i]);
				} catch (Exception e) {
					SDFSLogger.getLog().error(
							"unable to traverse " + this.getPath());
				}
			}
			return df;
		} else {
			return null;
		}
	}

	public boolean renameTo(String dest) throws IOException {
		return renameTo(dest, true);
	}

	public boolean renameTo(String dest, boolean propigateEvent)
			throws IOException {
		writeLock.lock();
		try {
			File f = new File(this.path);
			if (this.symlink) {
				Files.deleteIfExists(f.toPath());
				Path dstP = Paths.get(dest);
				Path srcP = Paths.get(this.path);
				try {
					Files.createSymbolicLink(dstP, srcP);
					return true;
				} catch (IOException e) {
					SDFSLogger.getLog().warn(
							"unable to rename file to " + dest, e);
					return false;
				}
			} else if (f.isDirectory()) {
				eventBus.post(new MFileDeleted(this,true));
				boolean rn = f.renameTo(new File(dest));
				if (rn) {
					this.path = dest;
				}
				eventBus.post(new MFileWritten(this));
				return rn;
			} else {
				MetaFileStore.removeMetaFile(dest, true);
				if (this.dfGuid != null)
					DedupFileStore.updateDedupFile(this);
				boolean rename = f.renameTo(new File(dest));

				if (rename) {
					this.dirty = true;
					eventBus.post(new MFileDeleted(this));
					if (SDFSLogger.isDebug())
						SDFSLogger.getLog()
								.debug("FileSystem rename succesful");

					this.path = dest;
					this.unmarshal();

				} else {
					SDFSLogger.getLog().warn("unable to move file");

				}
				return rename;
			}
		} finally {
			writeLock.unlock();
		}
	}

	public boolean exists() {
		writeLock.lock();

		try {
			return new File(this.path).exists();
		} finally {
			writeLock.unlock();
		}
	}

	public String getAbsolutePath() {
		return this.path;
	}

	public String getCanonicalPath() {
		return this.path;
	}

	public String getParent() {
		return new File(this.path).getParent();
	}

	public boolean canExecute() {
		return execute;
	}

	public boolean canRead() {
		return this.read;
	}

	public boolean canWrite() {
		return this.write;
	}

	public boolean setExecutable(boolean executable, boolean ownerOnly,
			boolean propigateEvent) {
		this.execute = executable;
		this.ownerExecOnly = ownerOnly;
		this.unmarshal();
		return true;
	}

	public boolean setExecutable(boolean executable, boolean propigateEvent) {
		this.execute = executable;
		this.dirty = true;
		this.unmarshal();
		return true;
	}

	public boolean setWritable(boolean writable, boolean ownerOnly,
			boolean propigateEvent) {
		this.write = writable;
		this.ownerWriteOnly = ownerOnly;
		this.dirty = true;
		this.unmarshal();
		return true;
	}

	public boolean setWritable(boolean writable, boolean propigateEvent) {
		this.write = writable;
		this.dirty = true;
		this.unmarshal();
		return true;
	}

	public boolean setReadable(boolean readable, boolean ownerOnly,
			boolean propigateEvent) {
		this.read = readable;
		this.ownerReadOnly = ownerOnly;
		this.unmarshal();
		return true;
	}

	public boolean setReadable(boolean readable, boolean propigateEvent) {
		this.read = readable;
		this.dirty = true;
		this.unmarshal();
		return true;
	}

	public void setReadOnly(boolean propigateEvent) {
		this.read = true;
		this.dirty = true;
		this.unmarshal();
	}

	public boolean isFile() {
		return new File(this.path).isFile();
	}

	public boolean isDirectory() {
		return new File(this.path).isDirectory();
	}

	public String getName() {
		return new File(this.path).getName();

	}

	public void setDirty(boolean dirty) {
		this.dirty = dirty;
	}

	/**
	 * @param lastModified
	 *            the lastModified to set
	 */

	public boolean setLastModified(long lastModified) {
		return setLastModified(lastModified, true);
	}

	/**
	 * @param lastModified
	 *            the lastModified to set
	 * @param propigateEvent
	 *            TODO
	 */

	public boolean setLastModified(long lastModified, boolean propigateEvent) {
		this.lastModified = lastModified;
		this.dirty = true;
		this.lastAccessed = lastModified;
		return true;
	}

	/**
	 * @return the length
	 */
	public long length() {
		if (SDFSLogger.isDebug())
			SDFSLogger.getLog().info("len=" + this.length);
		return length;
	}

	/**
	 * @param length
	 *            the length to set
	 */
	public void setLength(long l, boolean serialize) {
		setLength(l, serialize, true);
	}

	/**
	 * @param propigateEvent
	 *            TODO
	 * @param length
	 *            the length to set
	 */
	public void setLength(long l, boolean serialize, boolean propigateEvent) {

		long len = l - this.length;
		Main.volume.updateCurrentSize(len, true);
		this.length = l;
		this.dirty = true;
		if (serialize)
			this.unmarshal();
	}

	/**
	 * @return the path to the file stub on disk
	 */
	public String getPath() {
		return path;
	}

	/**
	 * 
	 * @param filePath
	 *            the path to the file
	 * @return true if the file exists
	 */
	public static boolean exists(String filePath) {
		File f = new File(filePath);
		return f.exists();
	}

	public boolean isAbsolute() {
		return true;
	}

	@Override
	public int hashCode() {
		return new File(this.path).hashCode();
	}

	/**
	 * writes all the metadata and the Dedup blocks to the dedup chunk service
	 */
	public void sync() {
		sync(true);
	}

	/**
	 * writes all the metadata and the Dedup blocks to the dedup chunk service
	 * 
	 * @param propigateEvent
	 *            TODO
	 */
	public void sync(boolean propigateEvent) {
		this.unmarshal();
	}

	/**
	 * 
	 * @param lastAccessed
	 */
	public void setLastAccessed(long lastAccessed) {
		setLastAccessed(lastAccessed, true);
	}

	/**
	 * 
	 * @param lastAccessed
	 * @param propigateEvent
	 *            TODO
	 */
	public void setLastAccessed(long lastAccessed, boolean propigateEvent) {
		// this.dirty = true;
		this.lastAccessed = lastAccessed;
	}

	public long getLastAccessed() {
		return lastAccessed;
	}

	public boolean isDirty() {
		return this.dirty;
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException,
			ClassNotFoundException {
		this.writeLock.lock();
		try {

			in.readLong();
			this.length = in.readLong();
			this.lastModified = in.readLong();
			this.lastAccessed = in.readLong();
			this.execute = in.readBoolean();
			this.read = in.readBoolean();
			this.write = in.readBoolean();
			this.hidden = in.readBoolean();
			this.ownerWriteOnly = in.readBoolean();
			this.ownerExecOnly = in.readBoolean();
			this.ownerReadOnly = in.readBoolean();
			int dfgl = in.readInt();
			if (dfgl == -1) {
				this.dfGuid = null;
			} else {
				byte[] dfb = new byte[dfgl];
				in.readFully(dfb);
				this.dfGuid = new String(dfb);
			}
			int gl = in.readInt();
			byte[] gfb = new byte[gl];
			in.readFully(gfb);
			this.guid = new String(gfb);

			int ml = in.readInt();
			if (ml == -1) {
				this.monitor = null;
			} else {
				byte[] mlb = new byte[ml];
				in.readFully(mlb);
				this.monitor = new IOMonitor(this);
				monitor.fromByteArray(mlb);
			}
			this.vmdk = in.readBoolean();
			// owner id is ignored
			this.owner_id = in.readInt();
			// group id is ignored
			this.group_id = in.readInt();
			byte[] hmb = new byte[in.readInt()];
			in.readFully(hmb);
			this.extendedAttrs = ByteUtils.deSerializeHashMap(hmb);
			this.dedup = in.readBoolean();
			try {
				if (in.available() > 0) {
					int vlen = in.readInt();
					byte[] vb = new byte[vlen];
					in.readFully(vb);
					this.version = new String(vb);
				}
				if (in.available() > 0) {
					this.attributes = in.readLong();
				}
				if (in.available() > 0) {
					this.mode = in.readInt();
				}
				/*
				 * if(in.available() > 0) { int vlen = in.readInt(); byte[] vb =
				 * new byte[vlen]; in.readFully(vb); this.backingFile = new
				 * String(vb); this.iterWeaveCP = in.readBoolean(); }
				 */
			} catch (Exception e) {

			}
		} finally {
			this.writeLock.unlock();
		}

	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		this.writeLock.lock();
		try {
			if (this.isSymlink())
				return;
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug(
						"writing out file=" + this.path + " df=" + this.dfGuid);
			out.writeLong(-1);
			out.writeLong(length);
			out.writeLong(lastModified);
			out.writeLong(lastAccessed);
			out.writeBoolean(execute);
			out.writeBoolean(read);
			out.writeBoolean(write);
			out.writeBoolean(hidden);
			out.writeBoolean(ownerWriteOnly);
			out.writeBoolean(ownerExecOnly);
			out.writeBoolean(ownerReadOnly);

			if (this.dfGuid != null) {
				byte[] dfb = this.dfGuid.getBytes();
				out.writeInt(dfb.length);
				out.write(dfb);
			} else {
				out.writeInt(-1);
			}
			byte[] dfb = this.guid.getBytes();
			out.writeInt(dfb.length);
			out.write(dfb);
			if (this.monitor != null) {
				byte[] mfb = this.monitor.toByteArray();
				out.writeInt(mfb.length);
				out.write(mfb);
			} else {
				out.writeInt(-1);
			}
			out.writeBoolean(vmdk);
			out.writeInt(owner_id);
			out.writeInt(group_id);
			byte[] hmb = ByteUtils.serializeHashMap(extendedAttrs);
			out.writeInt(hmb.length);
			out.write(hmb);
			out.writeBoolean(dedup);
			byte[] vb = this.version.getBytes();
			out.writeInt(vb.length);
			out.write(vb);
			out.writeLong(attributes);
			out.writeInt(this.mode);
			/*
			 * if(this.backingFile == null) out.writeInt(0); else { byte [] bb =
			 * this.backingFile.getBytes(); out.writeInt(bb.length);
			 * out.write(bb); } out.writeBoolean(this.iterWeaveCP);
			 */
		} finally {
			this.writeLock.unlock();
		}
	}

	public Element toXML(Document doc) throws ParserConfigurationException,
			DOMException, IOException {
		Element root = doc.createElement("file-info");
		root.setAttribute("file-name", this.getName());
		root.setAttribute("sdfs-path", this.getPath());
		if (this.isFile()) {
			root.setAttribute("type", "file");
			root.setAttribute("atime", Long.toString(this.getLastAccessed()));
			root.setAttribute("mtime", Long.toString(this.lastModified()));
			root.setAttribute("ctime", Long.toString(-1L));
			root.setAttribute("hidden", Boolean.toString(this.isHidden()));
			root.setAttribute("size", Long.toString(this.length()));
			try {
				root.setAttribute("open",
						Boolean.toString(DedupFileStore.fileOpen(this)));
			} catch (NullPointerException e) {
				root.setAttribute("open", Boolean.toString(false));
			}
			root.setAttribute("file-guid", this.getGUID());
			root.setAttribute("dedup-map-guid", this.getDfGuid());
			root.setAttribute("dedup", Boolean.toString(this.isDedup()));
			root.setAttribute("vmdk", Boolean.toString(this.isVmdk()));
			if (symlink) {
				root.setAttribute("symlink", Boolean.toString(this.isSymlink()));
				root.setAttribute("symlink-path", this.getSymlinkPath());
			}

			Element monEl = this.getIOMonitor().toXML(doc);
			root.appendChild(monEl);
		}
		if (this.isDirectory()) {
			Path p = Paths.get(this.getPath());
			File f = new File(this.getPath());
			root.setAttribute("type", "directory");
			BasicFileAttributes attrs = Files.readAttributes(p,
					BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			root.setAttribute("atime",
					Long.toString(attrs.lastAccessTime().toMillis()));
			root.setAttribute("mtime",
					Long.toString(attrs.lastModifiedTime().toMillis()));
			root.setAttribute("ctime",
					Long.toString(attrs.creationTime().toMillis()));
			root.setAttribute("hidden", Boolean.toString(f.isHidden()));
			root.setAttribute("size", Long.toString(attrs.size()));
			if (symlink) {
				root.setAttribute("symlink", Boolean.toString(this.isSymlink()));
				root.setAttribute("symlink-path", this.getSymlinkPath());
			}
		}

		return root;
	}

	public boolean isSymlink() {
		return symlink;
	}

	public void setSymlink(boolean symlink) {
		setSymlink(symlink, true);
	}

	public void setSymlink(boolean symlink, boolean propigateEvent) {
		this.symlink = symlink;
	}

	public String getSymlinkPath() {
		return symlinkPath;
	}

	public void setSymlinkPath(String symlinkPath) {
		setSymlinkPath(symlinkPath, true);
	}

	public void setSymlinkPath(String symlinkPath, boolean propigateEvent) {
		this.symlinkPath = symlinkPath;
	}

	public long getAttributes() {
		return attributes;
	}

	public void setAttributes(long attributes) {
		this.attributes = attributes;
	}
}
