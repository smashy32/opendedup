package org.opendedup.sdfs.mgmt;

import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;

import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.notification.SDFSEvent;
import org.opendedup.sdfs.replication.ArchiveImporter;
import org.w3c.dom.Element;

public class ImportArchiveCmd implements Runnable {
	String archive;
	String dest;
	String server;
	String password;
	int port;
	int maxSz;
	boolean useSSL;
	SDFSEvent evt;

	public Element getResult(String archive, String dest, String server,
			String password, int port, int maxSz, boolean useSSL)
			throws IOException {
		this.useSSL = useSSL;
		return importArchive(archive, dest, server, password, port, maxSz);
	}

	private Element importArchive(String archive, String dest, String server,
			String password, int port, int maxSz) throws IOException {
		this.archive = archive;
		this.dest = dest;
		this.server = server;
		this.password = password;
		this.port = port;
		this.maxSz = maxSz;

		evt = SDFSEvent.importEvent("Importing " + archive + " from " + server
				+ ":" + port + " to " + dest);
		evt.curCt = 0;
		evt.maxCt = 2;
		Thread th = new Thread(this);
		th.start();
		try {
			return evt.toXML();
		} catch (ParserConfigurationException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void run() {
		String sc = " not successful";
		try {

			new ArchiveImporter().importArchive(archive, dest, server,
					password, port, maxSz, evt, useSSL);
			sc = "successful";
		} catch (Throwable e) {
			SDFSLogger.getLog().error(
					"Unable to import archive [" + archive + "] "
							+ "Destination [" + dest + "] because :"
							+ e.toString(), e);

		} finally {
			SDFSLogger.getLog().info("Exited Replication task [" + sc + "]");
		}

	}

}
